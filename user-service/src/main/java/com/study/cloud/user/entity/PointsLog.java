package com.study.cloud.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 积分变动流水，映射 t_points_log。
 *
 * 作用：消息消费幂等。
 * 每消费一条"加积分"消息就插入一条流水，biz_no 上有唯一索引：
 * - 插入成功 → 首次消费，继续加积分
 * - 主键/唯一键冲突 → 重复消息，直接跳过
 * 流水和积分更新在同一个本地事务里，要么都成功要么都不生效。
 */
@Data
@TableName("t_points_log")
public class PointsLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 幂等键：ORDER-{orderId} 或事务消息 UUID */
    private String bizNo;

    /** 订单 ID（事务消息半消息阶段可能为 null） */
    private Long orderId;

    private Long userId;

    /** 本次增加的积分 */
    private Integer points;

    /** 来源 Topic：order-topic / order-tx-topic */
    private String sourceTopic;

    /** 创建时间，插入时不传，由数据库 DEFAULT CURRENT_TIMESTAMP 自动生成 */
    private LocalDateTime createTime;
}
