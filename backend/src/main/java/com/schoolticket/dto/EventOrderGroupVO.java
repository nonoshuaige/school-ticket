package com.schoolticket.dto;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class EventOrderGroupVO {
    private Long eventId;
    private String eventTitle;
    private String eventVenue;
    private LocalDateTime eventStartTime;
    private List<OrderVO> orders;
}
