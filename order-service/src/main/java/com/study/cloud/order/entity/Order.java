package com.study.cloud.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单实体，映射 order_db.t_order。
 */
@Data
@TableName("t_order")
public class Order {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 下单用户 id */
    private Long userId;

    /** 商品名称 */
    private String product;

    /** 订单金额（同时从用户余额扣减） */
    private BigDecimal amount;

    /** 订单状态：0 已创建 1 已支付 */
    private Integer status;

    private LocalDateTime createTime;
}
