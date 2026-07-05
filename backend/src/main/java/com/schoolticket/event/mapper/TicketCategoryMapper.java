package com.schoolticket.event.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.schoolticket.event.entity.TicketCategory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface TicketCategoryMapper extends BaseMapper<TicketCategory> {
    @Update("""
            UPDATE ticket_category
            SET remaining_quantity = remaining_quantity - #{quantity}
            WHERE ticket_id = #{ticketId}
              AND remaining_quantity >= #{quantity}
            """)
    int deductStock(@Param("ticketId") Long ticketId, @Param("quantity") int quantity);

    @Update("""
            UPDATE ticket_category
            SET remaining_quantity = remaining_quantity + #{quantity}
            WHERE ticket_id = #{ticketId}
            """)
    int replenishStock(@Param("ticketId") Long ticketId, @Param("quantity") int quantity);
}
