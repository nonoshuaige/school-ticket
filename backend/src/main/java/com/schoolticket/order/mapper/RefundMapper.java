package com.schoolticket.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.schoolticket.order.entity.Refund;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface RefundMapper extends BaseMapper<Refund> {
    @Update("""
            UPDATE refund
            SET status = 9, update_time = NOW()
            WHERE refund_id = #{refundId}
              AND status = 0
            """)
    int claimPending(@Param("refundId") String refundId);
}
