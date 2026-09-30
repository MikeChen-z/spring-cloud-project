-- Spring Cloud Study 数据库初始化脚本
-- 在 MySQL 中执行：mysql -u root -p < sql/init.sql
-- 或用 Navicat / DBeaver 直接粘贴运行

CREATE DATABASE IF NOT EXISTS study_cloud DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_general_ci;
USE study_cloud;

-- 用户表
DROP TABLE IF EXISTS t_user;
CREATE TABLE t_user (
    id       BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    name     VARCHAR(64)  NOT NULL DEFAULT ''     COMMENT '用户名（登录名）',
    email    VARCHAR(128) NOT NULL DEFAULT ''     COMMENT '邮箱',
    password VARCHAR(128) NOT NULL DEFAULT ''     COMMENT '密码（学习用明文；生产必须用 BCrypt 加盐哈希存储）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- 初始数据（密码统一为 123456，明文仅用于本地学习）
INSERT INTO t_user (id, name, email, password) VALUES
    (1, '张三', 'zhangsan@example.com', '123456'),
    (2, '李四', 'lisi@example.com',     '123456'),
    (3, '王五', 'wangwu@example.com',   '123456');
