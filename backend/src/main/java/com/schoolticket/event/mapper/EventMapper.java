package com.schoolticket.event.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.schoolticket.event.entity.Event;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface EventMapper extends BaseMapper<Event> {
}
