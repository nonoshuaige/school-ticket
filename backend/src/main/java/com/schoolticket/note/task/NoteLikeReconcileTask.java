package com.schoolticket.note.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schoolticket.note.entity.NoteLike;
import com.schoolticket.note.mapper.NoteLikeMapper;
import com.schoolticket.note.service.RedisNoteRankingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class NoteLikeReconcileTask {

    private static final long RECONCILE_DELAY_MS = 30_000L;
    private static final int BATCH_SIZE = 100;

    private final RedisNoteRankingService noteRankingService;
    private final NoteLikeMapper noteLikeMapper;

    @Scheduled(fixedRate = 60000)
    public void reconcileLikeCounts() {
        Set<Long> noteIds = noteRankingService.pollDueLikeDirtyIds(RECONCILE_DELAY_MS, BATCH_SIZE);
        if (noteIds.isEmpty()) return;

        for (Long noteId : noteIds) {
            long actualCount = noteLikeMapper.selectCount(
                    new LambdaQueryWrapper<NoteLike>().eq(NoteLike::getNoteId, noteId));
            noteRankingService.setLikeCount(noteId, actualCount);
            log.info("Note like count reconciled: noteId={} count={}", noteId, actualCount);
        }
    }
}
