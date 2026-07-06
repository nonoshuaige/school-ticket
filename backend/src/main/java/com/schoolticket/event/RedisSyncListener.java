package com.schoolticket.event;

import com.schoolticket.note.service.RedisNoteRankingService;
import com.schoolticket.user.service.RedisFollowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisSyncListener {

    private final RedisNoteRankingService noteRankingService;
    private final RedisFollowService followService;
    private final StringRedisTemplate redis;

    private static final String KEY_LATEST  = "note:latest";
    private static final String KEY_MINE    = "note:mine:%d";

    @Value("${feed.big-v-threshold:10000}")
    private int bigVThreshold;

    /** 创建笔记事件 → 加入 latest + mine ZSET + 推拉结合 fanout */
    @RabbitListener(queues = "#{noteCreateQueue.name}")
    public void handleNoteCreate(Map<String, Object> msg) {
        try {
            Long noteId = ((Number) msg.get("noteId")).longValue();
            Long userId = ((Number) msg.get("userId")).longValue();
            long score  = ((Number) msg.get("createTime")).longValue();
            boolean bigV = Boolean.TRUE.equals(msg.get("bigV"));
            redis.opsForZSet().add(KEY_LATEST, String.valueOf(noteId), score);
            redis.opsForZSet().add(String.format(KEY_MINE, userId), String.valueOf(noteId), score);

            if (bigV) {
                noteRankingService.markBigV(userId);
            } else {
                @SuppressWarnings("unchecked")
                List<String> fanIdList = (List<String>) msg.get("fanIds");
                if (fanIdList != null && !fanIdList.isEmpty()) {
                    Set<Long> fanIds = fanIdList.stream().map(Long::valueOf).collect(Collectors.toSet());
                    noteRankingService.fanoutToFollowers(userId, noteId, score, fanIds);
                }
            }
            log.info("Note create synced: noteId={} userId={} bigV={}", noteId, userId, bigV);
        } catch (Exception e) {
            log.error("handleNoteCreate error", e);
        }
    }

    /** 删除笔记事件 → 从 latest + mine ZSET 清除 + VO 缓存 + 收件箱 */
    @RabbitListener(queues = "#{noteDeleteQueue.name}")
    public void handleNoteDelete(Map<String, Object> msg) {
        try {
            Long noteId = ((Number) msg.get("noteId")).longValue();
            Long userId = msg.get("userId") != null ? ((Number) msg.get("userId")).longValue() : null;
            noteRankingService.removeNoteById(noteId, userId);
            noteRankingService.deleteNoteVO(noteId);
            log.info("Note delete synced to Redis: noteId={} userId={}", noteId, userId);
        } catch (Exception e) {
            log.error("handleNoteDelete error", e);
        }
    }

    /** 关注/取关事件 → 更新 follow/fan ZSET + 收件箱回填/清理 */
    @RabbitListener(queues = "#{followQueue.name}")
    public void handleFollow(Map<String, Object> msg) {
        try {
            String action     = (String) msg.get("action");
            Long followerId   = ((Number) msg.get("followerId")).longValue();
            Long userId       = ((Number) msg.get("userId")).longValue();
            if ("follow".equals(action)) {
                followService.addFollow(followerId, userId);
                noteRankingService.markUserActive(followerId, Collections.singleton(userId));
                // 关注普通/中腰部作者时回填收件箱；大V 读时从发件箱拉取
                if (followService.getFansCount(userId) < bigVThreshold) {
                    noteRankingService.backfillInboxForNewFan(followerId, userId);
                }
            } else if ("unfollow".equals(action)) {
                followService.removeFollow(followerId, userId);
                noteRankingService.removeActiveFan(userId, followerId);
                // 取关时：清理该用户笔记
                noteRankingService.removeAuthorFromInbox(followerId, userId);
            }
            log.info("Follow synced to Redis: action={} follower={} user={}", action, followerId, userId);
        } catch (Exception e) {
            log.error("handleFollow error", e);
        }
    }
}
