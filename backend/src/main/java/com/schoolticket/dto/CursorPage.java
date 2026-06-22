package com.schoolticket.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CursorPage<T> {
    private List<T> records;
    private String nextCursor;
    private boolean hasMore;
    private long total;

    public static <T> CursorPage<T> of(List<T> records, String nextCursor, boolean hasMore, long total) {
        return new CursorPage<>(records, nextCursor, hasMore, total);
    }
}
