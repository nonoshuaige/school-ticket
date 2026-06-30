package com.schoolticket.event.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schoolticket.event.entity.Event;
import com.schoolticket.event.entity.TicketCategory;
import com.schoolticket.event.mapper.EventMapper;
import com.schoolticket.event.mapper.TicketCategoryMapper;
import com.schoolticket.order.entity.Order;
import com.schoolticket.order.mapper.OrderMapper;
import com.schoolticket.order.service.OrderLuaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventService {

    private final EventMapper eventMapper;
    private final TicketCategoryMapper ticketCategoryMapper;
    private final OrderMapper orderMapper;
    private final OrderLuaService orderLuaService;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    private static final String HOT_POOL_KEY = "event:pool:hot";
    private static final String WARMUP_POOL_KEY = "event:pool:warmup";
    private static final String EVENT_VO_PREFIX = "event:vo:";
    private static final String EVENT_TICKETS_PREFIX = "event:tickets:";

    // ===================== 查询 =====================

    /**
     * 活动列表 —— Redis ZSET 游标分页，miss 时 MySQL 兜底并回填
     */
    public IPage<Event> getEventList(Integer status, Integer page, Integer pageSize) {
        // 选择对应的 pool
        String poolKey = toPoolKey(status);

        if (poolKey != null && Boolean.TRUE.equals(redis.hasKey(poolKey))) {
            return getEventListFromRedis(poolKey, status, page, pageSize);
        }

        // Redis miss → MySQL 兜底
        IPage<Event> result = getEventListFromMySQL(status, page, pageSize);
        // 异步回填 Redis（不阻塞响应）
        if (!result.getRecords().isEmpty()) {
            cacheEvents(result.getRecords());
        }
        return result;
    }

    /**
     * 活动详情 —— Redis Hash 优先，miss 时 MySQL 兜底并回填
     */
    public Map<String, Object> getEventDetail(Long eventId) {
        String voKey = EVENT_VO_PREFIX + eventId;
        String ticketsJson = redis.opsForValue().get(EVENT_TICKETS_PREFIX + eventId);

        if (ticketsJson != null) {
            String eventJson = redis.opsForValue().get(voKey);
            if (eventJson != null) {
                Event event = fromJson(eventJson, Event.class);
                List<TicketCategory> tickets = fromJsonList(ticketsJson, TicketCategory.class);
                Map<String, Object> result = new HashMap<>();
                result.put("event", event);
                result.put("tickets", tickets);
                return result;
            }
        }

        // Redis miss → MySQL
        Event event = eventMapper.selectById(eventId);
        if (event == null) return null;
        List<TicketCategory> tickets = ticketCategoryMapper.selectList(
                new LambdaQueryWrapper<TicketCategory>()
                        .eq(TicketCategory::getEventId, eventId)
                        .orderByAsc(TicketCategory::getPrice));
        cacheEventWithTickets(event, tickets);

        Map<String, Object> result = new HashMap<>();
        result.put("event", event);
        result.put("tickets", tickets);
        return result;
    }

    /**
     * 票档列表 —— Redis 优先
     */
    public List<TicketCategory> getTicketsByEvent(Long eventId) {
        String ticketsJson = redis.opsForValue().get(EVENT_TICKETS_PREFIX + eventId);
        if (ticketsJson != null) {
            return fromJsonList(ticketsJson, TicketCategory.class);
        }
        List<TicketCategory> tickets = ticketCategoryMapper.selectList(
                new LambdaQueryWrapper<TicketCategory>()
                        .eq(TicketCategory::getEventId, eventId)
                        .orderByAsc(TicketCategory::getPrice));
        if (!tickets.isEmpty()) {
            cacheTickets(eventId, tickets, getEventExpireAt(tickets.get(0).getEventId()));
        }
        return tickets;
    }

    /**
     * 批量获取活动摘要（轻量版，供笔记流/详情使用）
     * Pipeline GET event:vo:{eventId} JSON → 提取关键字段
     * miss 时查 MySQL + 回填 Redis
     */
    public Map<Long, Map<String, Object>> batchGetEventSummaries(List<Long> eventIds) {
        Map<Long, Map<String, Object>> result = new LinkedHashMap<>();
        if (eventIds == null || eventIds.isEmpty()) return result;

        List<byte[]> rawKeys = eventIds.stream()
                .map(eid -> (EVENT_VO_PREFIX + eid).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .collect(Collectors.toList());

        List<Object> pipeResults = redis.executePipelined(
                (org.springframework.data.redis.connection.RedisConnection connection) -> {
            for (byte[] key : rawKeys) {
                connection.stringCommands().get(key);
            }
            return null;
        });

        Set<Long> missEids = new LinkedHashSet<>();
        for (int i = 0; i < eventIds.size(); i++) {
            Object obj = pipeResults.get(i);
            Long eid = eventIds.get(i);
            if (obj instanceof String json && !json.isEmpty()) {
                try {
                    Event event = fromJson(json, Event.class);
                    result.put(eid, toSummaryMap(event));
                } catch (Exception e) {
                    missEids.add(eid);
                }
            } else {
                missEids.add(eid);
            }
        }

        // MySQL 兜底 + 回填
        if (!missEids.isEmpty()) {
            List<Event> missEvents = eventMapper.selectBatchIds(missEids);
            for (Event event : missEvents) {
                fillMinPrice(event, ticketCategoryMapper.selectList(
                        new LambdaQueryWrapper<TicketCategory>()
                                .eq(TicketCategory::getEventId, event.getEventId())));
                cacheEvents(Collections.singletonList(event));
                result.put(event.getEventId(), toSummaryMap(event));
            }
        }

        return result;
    }

    private Map<String, Object> toSummaryMap(Event event) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("eventId", event.getEventId());
        summary.put("title", event.getTitle());
        summary.put("posterUrl", event.getPosterUrl());
        summary.put("minPrice", event.getMinPrice() != null ? event.getMinPrice().doubleValue() : 0);
        summary.put("eventStartTime", event.getEventStartTime() != null ? event.getEventStartTime().toString() : null);
        summary.put("saleStartTime", event.getSaleStartTime() != null ? event.getSaleStartTime().toString() : null);
        summary.put("saleEndTime", event.getSaleEndTime() != null ? event.getSaleEndTime().toString() : null);
        summary.put("venue", event.getVenue());
        return summary;
    }

    public Map<String, Object> getPurchaseStatus(Long eventId, Long userId) {
        Map<String, Object> result = new HashMap<>();
        result.put("purchasedTicketId", null);
        result.put("purchasedQuantity", 0);
        result.put("maxQuantity", 5);

        if (userId == null) return result;

        List<TicketCategory> allTickets = getTicketsByEvent(eventId);
        List<Long> ticketIds = allTickets.stream().map(TicketCategory::getTicketId).collect(Collectors.toList());
        if (ticketIds.isEmpty()) return result;

        List<Order> existingOrders = orderMapper.selectList(
                new LambdaQueryWrapper<Order>()
                        .eq(Order::getUserId, userId)
                        .in(Order::getTicketId, ticketIds)
                        .notIn(Order::getStatus, 2, 3));

        if (!existingOrders.isEmpty()) {
            Long purchasedTicketId = existingOrders.get(0).getTicketId();
            int totalQty = existingOrders.stream().mapToInt(Order::getQuantity).sum();
            result.put("purchasedTicketId", purchasedTicketId);
            result.put("purchasedQuantity", totalQty);
        }
        return result;
    }

    // ===================== Redis 操作 =====================

    private IPage<Event> getEventListFromRedis(String poolKey, Integer status, Integer page, Integer pageSize) {
        Long total = redis.opsForZSet().size(poolKey);
        if (total == null || total == 0) {
            return new Page<>(page, pageSize, 0);
        }

        // 拉取比当前页更大的范围，补偿后续 status 过滤造成的损耗
        int fetchSize = pageSize * 3;
        int offset = (page - 1) * pageSize;
        Set<String> allInRange = redis.opsForZSet().range(poolKey, offset, offset + fetchSize - 1);
        if (allInRange == null || allInRange.isEmpty()) {
            return new Page<>(page, pageSize, total);
        }

        List<Long> candidateIds = allInRange.stream().map(Long::parseLong).collect(Collectors.toList());
        List<Long> cleanIds = new ArrayList<>();   // 状态匹配的有效 ID
        List<String> staleIds = new ArrayList<>(); // 需从 pool 清除的 ID

        for (Long id : candidateIds) {
            String json = redis.opsForValue().get(EVENT_VO_PREFIX + id);
            Event event = null;
            if (json != null) {
                event = fromJson(json, Event.class);
            } else {
                // VO 缓存缺失，从 MySQL 补
                event = eventMapper.selectById(id);
                if (event != null) {
                    fillMinPrice(event, ticketCategoryMapper.selectList(
                            new LambdaQueryWrapper<TicketCategory>().eq(TicketCategory::getEventId, id)));
                    cacheEvents(Collections.singletonList(event));
                }
            }

            if (event == null) {
                staleIds.add(String.valueOf(id)); // DB 也不存在，清除
            } else if (!isTimeValidForStatus(event, status)) {
                staleIds.add(String.valueOf(id)); // 时间窗口不符（已过期/已开售），清除
            } else {
                cleanIds.add(id);
            }
        }

        // 清除脏数据
        if (!staleIds.isEmpty()) {
            for (String sid : staleIds) {
                redis.opsForZSet().remove(poolKey, sid);
            }
            total = redis.opsForZSet().size(poolKey);
        }

        // 批量加载有效 VO
        List<Event> events = new ArrayList<>();
        for (Long id : cleanIds) {
            String json = redis.opsForValue().get(EVENT_VO_PREFIX + id);
            if (json != null) {
                events.add(fromJson(json, Event.class));
            }
        }

        events.sort(Comparator.comparing(Event::getEventStartTime));
        List<Event> pageEvents = events.size() > pageSize
                ? events.subList(0, pageSize) : events;

        Page<Event> result = new Page<>(page, pageSize, total != null ? total : pageEvents.size());
        result.setRecords(pageEvents);
        return result;
    }

    private IPage<Event> getEventListFromMySQL(Integer status, Integer page, Integer pageSize) {
        LambdaQueryWrapper<Event> wrapper = new LambdaQueryWrapper<Event>()
                .orderByAsc(Event::getEventStartTime);
        addTimeCondition(wrapper, status);
        IPage<Event> result = eventMapper.selectPage(new Page<>(page, pageSize), wrapper);
        fillMinPrices(result.getRecords());
        return result;
    }

    /**
     * 时间窗口校验：热卖中要求 saleEndTime > now，预热中要求 saleStartTime > now
     */
    private boolean isTimeValidForStatus(Event event, Integer status) {
        if (status == null) return true;
        LocalDateTime now = LocalDateTime.now();
        if (status == 1) return !event.getSaleStartTime().isAfter(now) && event.getSaleEndTime().isAfter(now);
        if (status == 0) return event.getSaleStartTime().isAfter(now);
        return true;
    }

    private void addTimeCondition(LambdaQueryWrapper<Event> wrapper, Integer status) {
        if (status == null) return;
        if (status == 1) {
            wrapper.le(Event::getSaleStartTime, LocalDateTime.now())
                   .gt(Event::getSaleEndTime, LocalDateTime.now());
        } else if (status == 0) {
            wrapper.gt(Event::getSaleStartTime, LocalDateTime.now());
        }
    }

    // ===================== 预热 =====================

    /**
     * 启动时预热：将所有未结束活动（saleEndTime > now）的数据同步到 Redis。
     * 按 saleStartTime 分流：已开售→hot pool，未开售→warmup pool
     */
    @PostConstruct
    public void preloadEvents() {
        try {
            // 拉取所有 saleEndTime > now 的活动（排除已结束）
            List<Event> allActive = eventMapper.selectList(
                    new LambdaQueryWrapper<Event>()
                            .gt(Event::getSaleEndTime, LocalDateTime.now()));

            LocalDateTime now = LocalDateTime.now();
            List<Event> hotEvents = allActive.stream()
                    .filter(e -> !e.getSaleStartTime().isAfter(now))
                    .collect(Collectors.toList());
            List<Event> warmupEvents = allActive.stream()
                    .filter(e -> e.getSaleStartTime().isAfter(now))
                    .collect(Collectors.toList());

            // 热卖中：库存 + VO + tickets + hot pool
            int stockCount = 0;
            for (Event event : hotEvents) {
                long expireAtSec = event.getSaleEndTime()
                        .atZone(ZoneId.of("Asia/Shanghai")).toEpochSecond();
                List<TicketCategory> tickets = ticketCategoryMapper.selectList(
                        new LambdaQueryWrapper<TicketCategory>().eq(TicketCategory::getEventId, event.getEventId()));
                for (TicketCategory ticket : tickets) {
                    orderLuaService.setStock(ticket.getTicketId(), ticket.getRemainingQuantity(), expireAtSec);
                    stockCount++;
                }
                cacheEventToRedis(event, tickets, expireAtSec);
                long score = event.getEventStartTime().atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
                redis.opsForZSet().add(HOT_POOL_KEY, String.valueOf(event.getEventId()), score);
                redis.expire(HOT_POOL_KEY, Duration.ofSeconds(expireAtSec - System.currentTimeMillis() / 1000));
            }

            // 预热中：VO + tickets + warmup pool
            long maxWarmupExpireSec = 0;
            for (Event event : warmupEvents) {
                long expireAtSec = event.getSaleStartTime()
                        .atZone(ZoneId.of("Asia/Shanghai")).toEpochSecond()
                        + 86400; // saleStartTime + 1天
                if (expireAtSec > maxWarmupExpireSec) {
                    maxWarmupExpireSec = expireAtSec;
                }
                List<TicketCategory> tickets = ticketCategoryMapper.selectList(
                        new LambdaQueryWrapper<TicketCategory>().eq(TicketCategory::getEventId, event.getEventId()));
                cacheEventToRedis(event, tickets, expireAtSec);
                long score = event.getEventStartTime().atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
                redis.opsForZSet().add(WARMUP_POOL_KEY, String.valueOf(event.getEventId()), score);
            }
            if (maxWarmupExpireSec > 0) {
                long relativeTtl = maxWarmupExpireSec - System.currentTimeMillis() / 1000;
                if (relativeTtl > 0) {
                    redis.expire(WARMUP_POOL_KEY, relativeTtl, TimeUnit.SECONDS);
                }
            }

            log.info("活动预热完成: {} 个热卖(含{}票档库存) + {} 个预热",
                    hotEvents.size(), stockCount, warmupEvents.size());
        } catch (Exception e) {
            log.error("活动预热失败", e);
        }
    }

    // ===================== 缓存回填 =====================

    private void cacheEvents(List<Event> events) {
        for (Event event : events) {
            List<TicketCategory> tickets = ticketCategoryMapper.selectList(
                    new LambdaQueryWrapper<TicketCategory>().eq(TicketCategory::getEventId, event.getEventId()));
            long expireAtSec = getEventExpireSec(event);
            cacheEventToRedis(event, tickets, expireAtSec);
            String poolKey = event.getStatus() == 1 ? HOT_POOL_KEY : WARMUP_POOL_KEY;
            long score = event.getEventStartTime().atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
            redis.opsForZSet().add(poolKey, String.valueOf(event.getEventId()), score);
        }
    }

    private void cacheEventWithTickets(Event event, List<TicketCategory> tickets) {
        long expireAtSec = getEventExpireSec(event);
        cacheEventToRedis(event, tickets, expireAtSec);
    }

    private void cacheEventToRedis(Event event, List<TicketCategory> tickets, long expireAtSec) {
        if (event.getMinPrice() == null) {
            fillMinPrice(event, tickets);
        }
        String voJson = toJson(event);
        String ticketsJson = toJson(tickets);
        long ttlSeconds = expireAtSec - System.currentTimeMillis() / 1000;
        if (ttlSeconds <= 0) ttlSeconds = 300; // 至少保留5分钟

        redis.opsForValue().set(EVENT_VO_PREFIX + event.getEventId(), voJson, ttlSeconds, TimeUnit.SECONDS);
        redis.opsForValue().set(EVENT_TICKETS_PREFIX + event.getEventId(), ticketsJson, ttlSeconds, TimeUnit.SECONDS);
    }

    private void cacheTickets(Long eventId, List<TicketCategory> tickets, long expireAtSec) {
        long ttlSeconds = expireAtSec - System.currentTimeMillis() / 1000;
        if (ttlSeconds <= 0) ttlSeconds = 300;
        redis.opsForValue().set(EVENT_TICKETS_PREFIX + eventId, toJson(tickets), ttlSeconds, TimeUnit.SECONDS);
    }

    // ===================== 辅助方法 =====================

    private void fillMinPrices(List<Event> events) {
        if (events.isEmpty()) return;
        List<Long> eventIds = events.stream().map(Event::getEventId).collect(Collectors.toList());
        List<TicketCategory> allTickets = ticketCategoryMapper.selectList(
                new LambdaQueryWrapper<TicketCategory>().in(TicketCategory::getEventId, eventIds));
        Map<Long, BigDecimal> minPriceMap = allTickets.stream()
                .collect(Collectors.groupingBy(TicketCategory::getEventId,
                        Collectors.collectingAndThen(
                                Collectors.minBy(Comparator.comparing(TicketCategory::getPrice)),
                                opt -> opt.map(TicketCategory::getPrice).orElse(BigDecimal.ZERO))));
        events.forEach(e -> e.setMinPrice(minPriceMap.getOrDefault(e.getEventId(), BigDecimal.ZERO)));
    }

    private void fillMinPrice(Event event, List<TicketCategory> tickets) {
        BigDecimal min = tickets.stream()
                .map(TicketCategory::getPrice)
                .min(Comparator.naturalOrder())
                .orElse(BigDecimal.ZERO);
        event.setMinPrice(min);
    }

    private long getEventExpireSec(Event event) {
        LocalDateTime now = LocalDateTime.now();
        // 未开售（预热中）：TTL = saleStartTime + 1天
        if (event.getSaleStartTime() != null && now.isBefore(event.getSaleStartTime())) {
            return event.getSaleStartTime().atZone(ZoneId.of("Asia/Shanghai")).toEpochSecond() + 86400;
        }
        // 已开售（热卖中或已结束）：TTL = saleEndTime
        return event.getSaleEndTime().atZone(ZoneId.of("Asia/Shanghai")).toEpochSecond();
    }

    private long getEventExpireAt(Long eventId) {
        Event event = eventMapper.selectById(eventId);
        return event != null ? getEventExpireSec(event) : System.currentTimeMillis() / 1000 + 300;
    }

    private String toPoolKey(Integer status) {
        if (status == null) return null;
        return status == 1 ? HOT_POOL_KEY : status == 0 ? WARMUP_POOL_KEY : null;
    }

    // ===================== JSON 工具 =====================

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("序列化失败", e);
        }
    }

    private <T> T fromJson(String json, Class<T> clazz) {
        try {
            return objectMapper.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("反序列化失败", e);
        }
    }

    private <T> List<T> fromJsonList(String json, Class<T> clazz) {
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, clazz));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("反序列化列表失败", e);
        }
    }
}
