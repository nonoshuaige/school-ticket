package com.schoolticket.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.schoolticket.order.entity.OrderEventLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface OrderEventLogMapper extends BaseMapper<OrderEventLog> {
    @Update("""
            UPDATE order_event_log
            SET status = 9, update_time = NOW()
            WHERE id = #{id}
              AND status = 0
            """)
    int claimPending(@Param("id") Long id);

    @Select("""
            SELECT COUNT(1)
            FROM order_event_log
            WHERE order_no = #{orderNo}
              AND event_type = #{eventType}
              AND status IN (0, 1, 9)
            """)
    int countActiveEvent(@Param("orderNo") String orderNo, @Param("eventType") int eventType);
}
