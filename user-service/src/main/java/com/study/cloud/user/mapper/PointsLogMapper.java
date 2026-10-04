package com.study.cloud.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.study.cloud.user.entity.PointsLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 积分流水数据访问层。
 * 幂等的核心就是 insert 时依赖 biz_no 唯一索引拦截重复消息。
 */
@Mapper
public interface PointsLogMapper extends BaseMapper<PointsLog> {
}
