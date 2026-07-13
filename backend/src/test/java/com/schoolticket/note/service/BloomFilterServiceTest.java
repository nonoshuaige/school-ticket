package com.schoolticket.note.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BloomFilterServiceTest {

    @Test
    void batchResultParsingConsumesEveryHashResultEvenAfterFirstMiss() {
        Map<Long, long[]> offsets = new LinkedHashMap<>();
        offsets.put(101L, new long[7]);
        offsets.put(202L, new long[7]);

        List<Object> pipelineResults = List.of(
                false, true, true, true, true, true, true,
                true, true, true, true, true, true, true);

        assertThat(BloomFilterService.resolveFresh(offsets, pipelineResults))
                .containsExactly(101L);
    }
}
