package com.study.cloud.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.study.cloud.order.entity.Order;
import org.apache.ibatis.annotations.Mapper;

/**
 * 订单数据访问层，继承 BaseMapper 即拥有通用 CRUD。
 */
@Mapper
public interface OrderMapper extends BaseMapper<Order> {
}
