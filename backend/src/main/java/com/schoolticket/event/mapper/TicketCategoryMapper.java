package com.schoolticket.event.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.schoolticket.event.entity.TicketCategory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TicketCategoryMapper extends BaseMapper<TicketCategory> {

    /**
     * 悲观锁查询票档（用于下单扣减名额时的并发控制）
     */
    @Select("SELECT * FROM ticket_category WHERE ticket_id = #{ticketId} FOR UPDATE")
    TicketCategory selectForUpdate(@Param("ticketId") Long ticketId);
}