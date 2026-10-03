package com.study.cloud.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 用户实体，映射 t_user 表。
 *
 * @TableName：指定表名（MyBatis-Plus 默认把类名 User 映射成 user，但我们表叫 t_user）
 * @TableId(type=AUTO)：主键自增策略
 * @TableField(exist=false)：标记非数据库字段（source 用于标识来源服务，不入库）
 */
@Data
@TableName("t_user")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String email;

    /**
     * 密码。@JsonIgnore：序列化返回时永远不输出该字段（即使是库中查出来的），
     * 避免 /users/{id} 等接口把密码泄露给调用方。
     */
    @JsonIgnore
    private String password;

    /** 账户余额（Seata AT 演示：下单时扣减） */
    private BigDecimal balance;

    /** 用户积分（RocketMQ 演示：消费订单消息累加） */
    private Integer points;

    /** 标识数据来源，不入库（供下游调试看是从哪个服务返回的） */
    @TableField(exist = false)
    private String source;
}
