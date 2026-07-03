package com.schoolticket.event.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schoolticket.event.entity.Event;
import com.schoolticket.event.entity.TicketCategory;
import com.schoolticket.event.mapper.EventMapper;
import com.schoolticket.event.mapper.TicketCategoryMapper;
import com.schoolticket.order.entity.Order;
import com.schoolticket.order.mapper.OrderMapper;
import com.schoolticket.order.service.OrderLuaService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private static final String EVENT_TICKET_IDS_PREFIX = "event:ticket_ids:";
    private static final String MUTEX_PREFIX = "mutex:event:";

    /** 物理 TTL 缓冲：逻辑过期后额外保留 30min，防止 key 被驱逐 */
    private static final long PHYSICAL_TTL_BUFFER_SEC = 1800;

    /** Pool 重建间隔：每 5min 首次请求触发异步重建 */
    private static final long POOL_REFRESH_INTERVAL_SEC = 300;

    /** 异步缓存重建线程池 */
    private final ExecutorService cacheRefreshPool = Executors.newFixedThreadPool(4);

    @PreDestroy
    public void shutdown() {
        cacheRefreshPool.shutdown();
    }

    // ===================== 查询 =====================

    /**
     * 活动列表 —— 逻辑过期 + 互斥锁防击穿
     */
    public IPage<Event> getEventList(Integer status, Integer page, Integer pageSize) {
        String poolKey = toPoolKey(status);
        if (poolKey == null) {
            return getEventListFromMySQL(status, page, pageSize);
        }

        // 1. Pool 物理存在 → 检查逻辑过期
        if (Boolean.TRUE.equals(redis.hasKey(poolKey))) {
            long nowSec = System.currentTimeMillis() / 1000;
            long expireAt = getPoolExpireAt(poolKey);

            if (nowSec < expireAt) {
                // 未过期 → 直接从缓存返回
                return getEventListFromRedis(poolKey, status, page, pageSize);
            }

            // 逻辑已过期 → 抢锁，异步重建，返回过期旧值（容忍短暂脏读）
            String mutexKey = mutexKey("pool", status);
            if (tryLock(mutexKey)) {
                cacheRefreshPool.submit(() -> {
                    try {
                        rebuildPool(status);
                    } catch (Exception e) {
                        log.error("异步重建pool失败 status={}", status, e);
                    } finally {
                        unlock(mutexKey);
                    }
                });
            }
            return getEventListFromRedis(poolKey, status, page, pageSize);
        }

        // 2. Pool 物理缺失 → 抢锁同步重建
        String mutexKey = mutexKey("pool", status);
        if (tryLock(mutexKey)) {
            try {
                rebuildPool(status);
                if (Boolean.TRUE.equals(redis.hasKey(poolKey))) {
                    return getEventListFromRedis(poolKey, status, page, pageSize);
                }
            } finally {
                unlock(mutexKey);
            }
        }

        // 其他线程正在重建 → 短暂等待后重试 Redis
        sleepUninterrupted(80);
        if (Boolean.TRUE.equals(redis.hasKey(poolKey))) {
            return getEventListFromRedis(poolKey, status, page, pageSize);
        }

        // 最终兜底
        return getEventListFromMySQL(status, page, pageSize);
    }

    /**
     * 活动详情 —— 逻辑过期 + 互斥锁
     */
    public Map<String, Object> getEventDetail(Long eventId) {
        String voKey = EVENT_VO_PREFIX + eventId;
        String ticketsKey = EVENT_TICKETS_PREFIX + eventId;

        String voJson = redis.opsForValue().get(voKey);
        String ticketsJson = redis.opsForValue().get(ticketsKey);

        long nowSec = System.currentTimeMillis() / 1000;
        boolean voFresh = voJson != null && !isLogicallyExpired(voJson, nowSec);
        boolean ticketsFresh = ticketsJson != null && !isLogicallyExpired(ticketsJson, nowSec);

        if (voFresh && ticketsFresh) {
            return buildDetailResult(unwrapData(voJson, Event.class),
                    unwrapDataList(ticketsJson, TicketCategory.class));
        }

        // 过期或缺失 → 抢锁刷新
        String mutexKey = mutexKey("vo", eventId);
        if (tryLock(mutexKey)) {
            try {
                Event event = eventMapper.selectById(eventId);
                if (event == null) return null;
                List<TicketCategory> tickets = ticketCategoryMapper.selectList(
                        new LambdaQueryWrapper<TicketCategory>()
                                .eq(TicketCategory::getEventId, eventId)
                                .orderByAsc(TicketCategory::getPrice));
                fillMinPrice(event, tickets);
                cacheEventToRedis(event, tickets, getEventLogicalExpireAt(event));
                return buildDetailResult(event, tickets);
            } finally {
                unlock(mutexKey);
            }
        }

        // 未获取锁 → 返回过期旧值兜底
        if (voJson != null && ticketsJson != null) {
            return buildDetailResult(unwrapData(voJson, Event.class),
                    unwrapDataList(ticketsJson, TicketCategory.class));
        }

        // 无旧值可兜底 → 短暂等待后重试
        sleepUninterrupted(80);
        voJson = redis.opsForValue().get(voKey);
        ticketsJson = redis.opsForValue().get(ticketsKey);
        if (voJson != null && ticketsJson != null) {
            return buildDetailResult(unwrapData(voJson, Event.class),
                    unwrapDataList(ticketsJson, TicketCategory.class));
        }

        // 最终兜底
        Event event = eventMapper.selectById(eventId);
        if (event == null) return null;
        List<TicketCategory> tickets = ticketCategoryMapper.selectList(
                new LambdaQueryWrapper<TicketCategory>()
                        .eq(TicketCategory::getEventId, eventId)
                        .orderByAsc(TicketCategory::getPrice));
        return buildDetailResult(event, tickets);
    }

    /**
     * 票档列表 —— 逻辑过期 + 互斥锁
     */
    public List<TicketCategory> getTicketsByEvent(Long eventId) {
        String key = EVENT_TICKETS_PREFIX + eventId;
        String json = redis.opsForValue().get(key);

        if (json != null && !isLogicallyExpired(json, System.currentTimeMillis() / 1000)) {
            return unwrapDataList(json, TicketCategory.class);
        }

        String mutexKey = mutexKey("tickets", eventId);
        if (tryLock(mutexKey)) {
            try {
                List<TicketCategory> tickets = ticketCategoryMapper.selectList(
                        new LambdaQueryWrapper<TicketCategory>()
                                .eq(TicketCategory::getEventId, eventId)
                                .orderByAsc(TicketCategory::getPrice));
                Event event = eventMapper.selectById(eventId);
                long expireAt = event != null
                        ? getEventLogicalExpireAt(event)
                        : System.currentTimeMillis() / 1000 + 300;
                if (!tickets.isEmpty()) {
                    cacheTickets(eventId, tickets, expireAt);
                }
                return tickets;
            } finally {
                unlock(mutexKey);
            }
        }

        // 返回旧值兜底
        if (json != null) {
            return unwrapDataList(json, TicketCategory.class);
        }

        sleepUninterrupted(80);
        json = redis.opsForValue().get(key);
        if (json != null) {
            return unwrapDataList(json, TicketCategory.class);
        }

        return ticketCategoryMapper.selectList(
                new LambdaQueryWrapper<TicketCategory>()
                        .eq(TicketCategory::getEventId, eventId)
                        .orderByAsc(TicketCategory::getPrice));
    }

    /**
     * 批量获取活动摘要 —— 支持逻辑过期格式
     */
    public Map<Long, Map<String, Object>> batchGetEventSummaries(List<Long> eventIds) {
        Map<Long, Map<String, Object>> result = new LinkedHashMap<>();
        if (eventIds == null || eventIds.isEmpty()) return result;

        List<byte[]> rawKeys = eventIds.stream()
                .map(eid -> (EVENT_VO_PREFIX + eid).getBytes(StandardCharsets.UTF_8))
                .collect(Collectors.toList());

        List<Object> pipeResults = redis.executePipelined(
                (org.springframework.data.redis.connection.RedisConnection connection) -> {
                    for (byte[] key : rawKeys) {
                        connection.stringCommands().get(key);
                    }
                    return null;
                });

        long nowSec = System.currentTimeMillis() / 1000;
        Set<Long> missEids = new LinkedHashSet<>();

        for (int i = 0; i < eventIds.size(); i++) {
            Object obj = pipeResults.get(i);
            Long eid = eventIds.get(i);
            if (obj instanceof String json && !json.isEmpty()) {
                try {
                    if (isLogicallyExpired(json, nowSec)) {
                        // 逻辑过期 → 先用旧值，标记需刷新
                        missEids.add(eid);
                    }
                    Event event = unwrapData(json, Event.class);
                    result.put(eid, toSummaryMap(event));
                } catch (Exception e) {
                    missEids.add(eid);
                }
            } else {
                missEids.add(eid);
            }
        }

        // MySQL 兜底 + 异步回填
        if (!missEids.isEmpty()) {
            List<Event> missEvents = eventMapper.selectBatchIds(missEids);
            for (Event event : missEvents) {
                fillMinPrice(event, ticketCategoryMapper.selectList(
                        new LambdaQueryWrapper<TicketCategory>()
                                .eq(TicketCategory::getEventId, event.getEventId())));
                cacheEventToRedis(event, ticketCategoryMapper.selectList(
                        new LambdaQueryWrapper<TicketCategory>()
                                .eq(TicketCategory::getEventId, event.getEventId())),
                        getEventLogicalExpireAt(event));
                result.put(event.getEventId(), toSummaryMap(event));
            }
        }

        return result;
    }

    public Map<String, Object> getPurchaseStatus(Long eventId, Long userId) {
        Map<String, Object> result = new HashMap<>();
        result.put("purchasedQuantity", 0);
        result.put("maxQuantity", 5);

        if (userId == null) return result;

        // 优先从 Redis Set 获取 event 下的 ticketId 列表
        List<Long> ticketIds = getTicketIdsByEvent(eventId);
        if (ticketIds.isEmpty()) return result;

        List<Order> existingOrders = orderMapper.selectList(
                new LambdaQueryWrapper<Order>()
                        .eq(Order::getUserId, userId)
                        .in(Order::getTicketId, ticketIds)
                        .notIn(Order::getStatus, 2, 3));

        if (!existingOrders.isEmpty()) {
            int totalQty = existingOrders.stream().mapToInt(Order::getQuantity).sum();
            result.put("purchasedQuantity", totalQty);
        }
        return result;
    }

    /**
     * 获取活动下的所有票档 ID（购票层用，优先 Redis Set）
     */
    private List<Long> getTicketIdsByEvent(Long eventId) {
        String idsKey = EVENT_TICKET_IDS_PREFIX + eventId;
        Set<String> cached = redis.opsForSet().members(idsKey);
        if (cached != null && !cached.isEmpty()) {
            return cached.stream().map(Long::parseLong).collect(Collectors.toList());
        }
        // miss → 从 MySQL 回填到 Redis Set
        List<TicketCategory> tickets = ticketCategoryMapper.selectList(
                new LambdaQueryWrapper<TicketCategory>()
                        .eq(TicketCategory::getEventId, eventId));
        if (!tickets.isEmpty()) {
            String[] ids = tickets.stream().map(t -> String.valueOf(t.getTicketId())).toArray(String[]::new);
            redis.opsForSet().add(idsKey, ids);
            // TTL: 取最晚 saleEndTime
            Event event = eventMapper.selectById(eventId);
            long ttl = event != null ? event.getSaleEndTime()
                    .atZone(ZoneId.of("Asia/Shanghai")).toEpochSecond()
                    - System.currentTimeMillis() / 1000 + PHYSICAL_TTL_BUFFER_SEC : 3600;
            redis.expire(idsKey, ttl, TimeUnit.SECONDS);
        }
        return tickets.stream().map(TicketCategory::getTicketId).collect(Collectors.toList());
    }

    // ===================== Redis 读取 =====================

    private IPage<Event> getEventListFromRedis(String poolKey, Integer status, Integer page, Integer pageSize) {
        Long total = redis.opsForZSet().size(poolKey);
        if (total == null || total == 0) {
            return new Page<>(page, pageSize, 0);
        }

        int fetchSize = pageSize * 3;
        int offset = (page - 1) * pageSize;
        Set<String> allInRange = redis.opsForZSet().range(poolKey, offset, offset + fetchSize - 1);
        if (allInRange == null || allInRange.isEmpty()) {
            return new Page<>(page, pageSize, total);
        }

        List<Long> candidateIds = allInRange.stream().map(Long::parseLong).collect(Collectors.toList());
        List<Long> cleanIds = new ArrayList<>();
        List<String> staleIds = new ArrayList<>();

        for (Long id : candidateIds) {
            String json = redis.opsForValue().get(EVENT_VO_PREFIX + id);
            Event event = null;
            if (json != null) {
                event = unwrapData(json, Event.class);
            } else {
                // VO 缓存缺失，从 MySQL 补
                event = eventMapper.selectById(id);
                if (event != null) {
                    fillMinPrice(event, ticketCategoryMapper.selectList(
                            new LambdaQueryWrapper<TicketCategory>().eq(TicketCategory::getEventId, id)));
                    cacheEventToRedis(event, ticketCategoryMapper.selectList(
                            new LambdaQueryWrapper<TicketCategory>().eq(TicketCategory::getEventId, id)),
                            getEventLogicalExpireAt(event));
                }
            }

            if (event == null) {
                staleIds.add(String.valueOf(id));
            } else if (!isTimeValidForStatus(event, status)) {
                staleIds.add(String.valueOf(id));
            } else {
                cleanIds.add(id);
            }
        }

        if (!staleIds.isEmpty()) {
            for (String sid : staleIds) {
                redis.opsForZSet().remove(poolKey, sid);
            }
            total = redis.opsForZSet().size(poolKey);
        }

        List<Event> events = new ArrayList<>();
        for (Long id : cleanIds) {
            String json = redis.opsForValue().get(EVENT_VO_PREFIX + id);
            if (json != null) {
                events.add(unwrapData(json, Event.class));
            }
        }

        events.sort(Comparator.comparing(Event::getEventStartTime));
        List<Event> pageEvents = events.size() > pageSize
                ? events.subList(0, pageSize) : events;

        Page<Event> result = new Page<>(page, pageSize, total != null ? total : pageEvents.size());
        result.setRecords(pageEvents);
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

    private Map<String, Object> buildDetailResult(Event event, List<TicketCategory> tickets) {
        Map<String, Object> result = new HashMap<>();
        result.put("event", event);
        result.put("tickets", tickets);
        return result;
    }

    // ===================== MySQL 兜底 =====================

    private IPage<Event> getEventListFromMySQL(Integer status, Integer page, Integer pageSize) {
        LambdaQueryWrapper<Event> wrapper = new LambdaQueryWrapper<Event>()
                .orderByAsc(Event::getEventStartTime);
        addTimeCondition(wrapper, status);
        IPage<Event> result = eventMapper.selectPage(new Page<>(page, pageSize), wrapper);
        fillMinPrices(result.getRecords());
        return result;
    }

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

    @PostConstruct
    public void preloadEvents() {
        try {
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

            // 热卖中
            long hotMaxExpireAt = 0;
            for (Event event : hotEvents) {
                long expireAtSec = event.getSaleEndTime()
                        .atZone(ZoneId.of("Asia/Shanghai")).toEpochSecond();
                List<TicketCategory> tickets = ticketCategoryMapper.selectList(
                        new LambdaQueryWrapper<TicketCategory>().eq(TicketCategory::getEventId, event.getEventId()));
                for (TicketCategory ticket : tickets) {
                    orderLuaService.setStock(ticket.getTicketId(), ticket.getRemainingQuantity(), expireAtSec);
                }
                long logicalExpireAt = getEventLogicalExpireAt(event);
                if (logicalExpireAt > hotMaxExpireAt) hotMaxExpireAt = logicalExpireAt;
                cacheEventToRedis(event, tickets, logicalExpireAt);
                long score = event.getEventStartTime().atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
                redis.opsForZSet().add(HOT_POOL_KEY, String.valueOf(event.getEventId()), score);
            }

            // 热卖 pool 物理 TTL
            if (hotMaxExpireAt > 0) {
                long ttl = hotMaxExpireAt - System.currentTimeMillis() / 1000 + PHYSICAL_TTL_BUFFER_SEC;
                if (ttl > 0) redis.expire(HOT_POOL_KEY, ttl, TimeUnit.SECONDS);
            }
            // 热卖 pool 逻辑过期（取最早的事件转换时间，但不超过 5min 后重建）
            setPoolExpireAt(HOT_POOL_KEY, System.currentTimeMillis() / 1000 + POOL_REFRESH_INTERVAL_SEC);

            // 预热中
            long warmupMaxExpireAt = 0;
            for (Event event : warmupEvents) {
                long logicalExpireAt = getEventLogicalExpireAt(event);
                if (logicalExpireAt > warmupMaxExpireAt) warmupMaxExpireAt = logicalExpireAt;
                List<TicketCategory> tickets = ticketCategoryMapper.selectList(
                        new LambdaQueryWrapper<TicketCategory>().eq(TicketCategory::getEventId, event.getEventId()));
                cacheEventToRedis(event, tickets, logicalExpireAt);
                long score = event.getEventStartTime().atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
                redis.opsForZSet().add(WARMUP_POOL_KEY, String.valueOf(event.getEventId()), score);
            }

            if (warmupMaxExpireAt > 0) {
                long ttl = warmupMaxExpireAt - System.currentTimeMillis() / 1000 + PHYSICAL_TTL_BUFFER_SEC;
                if (ttl > 0) redis.expire(WARMUP_POOL_KEY, ttl, TimeUnit.SECONDS);
            }
            setPoolExpireAt(WARMUP_POOL_KEY, System.currentTimeMillis() / 1000 + POOL_REFRESH_INTERVAL_SEC);

            log.info("活动预热完成: {} 个热卖 + {} 个预热", hotEvents.size(), warmupEvents.size());
        } catch (Exception e) {
            log.error("活动预热失败", e);
        }
    }

    // ===================== Pool 重建 =====================

    /**
     * 全量重建单个 pool：查 DB → 逐条缓存 VO+tickets → 重建 ZSET → 设过期
     */
    private void rebuildPool(int status) {
        LocalDateTime now = LocalDateTime.now();
        List<Event> events;
        String poolKey = toPoolKey(status);

        if (status == 1) {
            events = eventMapper.selectList(new LambdaQueryWrapper<Event>()
                    .le(Event::getSaleStartTime, now)
                    .gt(Event::getSaleEndTime, now)
                    .orderByAsc(Event::getEventStartTime));
        } else {
            events = eventMapper.selectList(new LambdaQueryWrapper<Event>()
                    .gt(Event::getSaleStartTime, now)
                    .orderByAsc(Event::getEventStartTime));
        }

        // 清空旧 pool
        redis.delete(poolKey);

        long maxLogicalExpireAt = 0;
        for (Event event : events) {
            List<TicketCategory> tickets = ticketCategoryMapper.selectList(
                    new LambdaQueryWrapper<TicketCategory>().eq(TicketCategory::getEventId, event.getEventId()));
            fillMinPrice(event, tickets);

            long logicalExpireAt = getEventLogicalExpireAt(event);
            if (logicalExpireAt > maxLogicalExpireAt) maxLogicalExpireAt = logicalExpireAt;

            cacheEventToRedis(event, tickets, logicalExpireAt);

            long score = event.getEventStartTime()
                    .atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
            redis.opsForZSet().add(poolKey, String.valueOf(event.getEventId()), score);
        }

        // 物理 TTL
        long physicalTtl = maxLogicalExpireAt - System.currentTimeMillis() / 1000 + PHYSICAL_TTL_BUFFER_SEC;
        if (physicalTtl > 0 && !events.isEmpty()) {
            redis.expire(poolKey, physicalTtl, TimeUnit.SECONDS);
        }

        // 逻辑过期时间
        setPoolExpireAt(poolKey, System.currentTimeMillis() / 1000 + POOL_REFRESH_INTERVAL_SEC);

        // 同步库存（热卖活动）
        if (status == 1) {
            for (Event event : events) {
                long stockExpireSec = event.getSaleEndTime()
                        .atZone(ZoneId.of("Asia/Shanghai")).toEpochSecond();
                for (TicketCategory ticket : ticketCategoryMapper.selectList(
                        new LambdaQueryWrapper<TicketCategory>().eq(TicketCategory::getEventId, event.getEventId()))) {
                    // 仅在库存 key 不存在时写入，避免覆盖 Lua 实时库存
                    if (Boolean.FALSE.equals(redis.hasKey("ticket:stock:" + ticket.getTicketId()))) {
                        orderLuaService.setStock(ticket.getTicketId(), ticket.getRemainingQuantity(), stockExpireSec);
                    }
                }
            }
        }

        log.info("Pool 重建完成: {} status={} 共 {} 个活动", poolKey, status, events.size());
    }

    // ===================== 缓存回填 =====================

    private void cacheEvents(List<Event> events) {
        for (Event event : events) {
            List<TicketCategory> tickets = ticketCategoryMapper.selectList(
                    new LambdaQueryWrapper<TicketCategory>().eq(TicketCategory::getEventId, event.getEventId()));
            long logicalExpireAt = getEventLogicalExpireAt(event);
            cacheEventToRedis(event, tickets, logicalExpireAt);
            String poolKey = event.getStatus() == 1 ? HOT_POOL_KEY : WARMUP_POOL_KEY;
            long score = event.getEventStartTime().atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
            redis.opsForZSet().add(poolKey, String.valueOf(event.getEventId()), score);
        }
    }

    private void cacheEventWithTickets(Event event, List<TicketCategory> tickets) {
        cacheEventToRedis(event, tickets, getEventLogicalExpireAt(event));
    }

    /**
     * 写入单条活动的 VO + tickets 到 Redis。
     * 格式：{"expireAt":..., "data":{...}}，物理 TTL = 逻辑过期 + 缓冲。
     */
    private void cacheEventToRedis(Event event, List<TicketCategory> tickets, long logicalExpireAtSec) {
        if (event.getMinPrice() == null) {
            fillMinPrice(event, tickets);
        }
        long physicalTtl = logicalExpireAtSec - System.currentTimeMillis() / 1000 + PHYSICAL_TTL_BUFFER_SEC;
        if (physicalTtl <= 0) physicalTtl = 300;

        // 展示层：活动 VO + 票档详情 JSON
        redis.opsForValue().set(EVENT_VO_PREFIX + event.getEventId(),
                wrapWithExpireAt(event, logicalExpireAtSec), physicalTtl, TimeUnit.SECONDS);
        redis.opsForValue().set(EVENT_TICKETS_PREFIX + event.getEventId(),
                wrapWithExpireAt(tickets, logicalExpireAtSec), physicalTtl, TimeUnit.SECONDS);

        // 购票层：event → ticketIds 映射 Set，供购买状态查询快速定位
        String idsKey = EVENT_TICKET_IDS_PREFIX + event.getEventId();
        redis.delete(idsKey);
        String[] ids = tickets.stream().map(t -> String.valueOf(t.getTicketId())).toArray(String[]::new);
        if (ids.length > 0) {
            redis.opsForSet().add(idsKey, ids);
            redis.expire(idsKey, physicalTtl, TimeUnit.SECONDS);
        }
    }

    private void cacheTickets(Long eventId, List<TicketCategory> tickets, long logicalExpireAtSec) {
        long physicalTtl = logicalExpireAtSec - System.currentTimeMillis() / 1000 + PHYSICAL_TTL_BUFFER_SEC;
        if (physicalTtl <= 0) physicalTtl = 300;
        redis.opsForValue().set(EVENT_TICKETS_PREFIX + eventId,
                wrapWithExpireAt(tickets, logicalExpireAtSec), physicalTtl, TimeUnit.SECONDS);

        // 同步更新 ticket_ids Set
        String idsKey = EVENT_TICKET_IDS_PREFIX + eventId;
        redis.delete(idsKey);
        String[] ids = tickets.stream().map(t -> String.valueOf(t.getTicketId())).toArray(String[]::new);
        if (ids.length > 0) {
            redis.opsForSet().add(idsKey, ids);
            redis.expire(idsKey, physicalTtl, TimeUnit.SECONDS);
        }
    }

    // ===================== 互斥锁（SET NX EX） =====================

    private boolean tryLock(String key) {
        return Boolean.TRUE.equals(
                redis.opsForValue().setIfAbsent(MUTEX_PREFIX + key, "1", 10, TimeUnit.SECONDS));
    }

    private void unlock(String key) {
        redis.delete(MUTEX_PREFIX + key);
    }

    private static String mutexKey(String type, Object id) {
        return type + ":" + id;
    }

    // ===================== Pool 逻辑过期 =====================

    private String poolExpireKey(String poolKey) {
        return poolKey + ":expire";
    }

    private long getPoolExpireAt(String poolKey) {
        String s = redis.opsForValue().get(poolExpireKey(poolKey));
        if (s != null) {
            try { return Long.parseLong(s); } catch (NumberFormatException e) { /* ignore */ }
        }
        return 0; // key 缺失 → 视为已过期
    }

    private void setPoolExpireAt(String poolKey, long expireAtSec) {
        // pool:expire key 物理 TTL 比逻辑过期更长，防驱逐
        long physicalTtl = expireAtSec - System.currentTimeMillis() / 1000 + PHYSICAL_TTL_BUFFER_SEC;
        if (physicalTtl > 0) {
            redis.opsForValue().set(poolExpireKey(poolKey), String.valueOf(expireAtSec),
                    physicalTtl, TimeUnit.SECONDS);
        }
    }

    // ===================== 逻辑过期封装 =====================

    /**
     * @return 活动缓存逻辑过期时间（epoch秒）
     *   热卖中 → saleEndTime
     *   预热中 → saleStartTime
     */
    private long getEventLogicalExpireAt(Event event) {
        LocalDateTime now = LocalDateTime.now();
        if (event.getSaleStartTime() != null && now.isBefore(event.getSaleStartTime())) {
            // 预热中：开售时转为热卖，需刷新
            return event.getSaleStartTime().atZone(ZoneId.of("Asia/Shanghai")).toEpochSecond();
        }
        // 热卖中：结束时下架
        return event.getSaleEndTime().atZone(ZoneId.of("Asia/Shanghai")).toEpochSecond();
    }

    /**
     * 将数据包装为带逻辑过期时间的 JSON：
     * {"expireAt":1234567890,"data":{原始JSON}}
     */
    private String wrapWithExpireAt(Object data, long expireAt) {
        return "{\"expireAt\":" + expireAt + ",\"data\":" + toJson(data) + "}";
    }

    /**
     * 判断缓存 value 是否逻辑过期。
     * 旧格式（无 expireAt 字段）视为未过期，兼容升级过渡期。
     */
    private boolean isLogicallyExpired(String cacheJson, long nowSec) {
        try {
            JsonNode node = objectMapper.readTree(cacheJson);
            if (node.has("expireAt")) {
                return node.get("expireAt").asLong() <= nowSec;
            }
            return false; // 旧格式 → 不判定为过期
        } catch (Exception e) {
            return false;
        }
    }

    /** 从带逻辑过期时间的 JSON 中提取数据部分 */
    private <T> T unwrapData(String cacheJson, Class<T> clazz) {
        try {
            JsonNode node = objectMapper.readTree(cacheJson);
            JsonNode dataNode = node.get("data");
            if (dataNode != null) {
                return objectMapper.treeToValue(dataNode, clazz);
            }
            // 兼容旧格式（无 expireAt 包裹）
            return objectMapper.treeToValue(node, clazz);
        } catch (Exception e) {
            throw new RuntimeException("反序列化失败", e);
        }
    }

    /** 从带逻辑过期时间的 JSON 中提取列表数据 */
    private <T> List<T> unwrapDataList(String cacheJson, Class<T> elementClass) {
        try {
            JsonNode node = objectMapper.readTree(cacheJson);
            JsonNode dataNode = node.get("data");
            JsonNode target = dataNode != null ? dataNode : node;
            return objectMapper.readValue(
                    objectMapper.treeAsTokens(target),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, elementClass));
        } catch (Exception e) {
            throw new RuntimeException("反序列化列表失败", e);
        }
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

    private String toPoolKey(Integer status) {
        if (status == null) return null;
        return status == 1 ? HOT_POOL_KEY : status == 0 ? WARMUP_POOL_KEY : null;
    }

    private static void sleepUninterrupted(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    // ===================== JSON 工具 =====================

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("序列化失败", e);
        }
    }
}
