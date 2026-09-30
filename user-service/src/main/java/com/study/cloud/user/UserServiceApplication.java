package com.study.cloud.user;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 用户服务启动类（服务提供者）。
 *
 * @EnableDiscoveryClient：开启服务注册与发现（Spring Cloud 2023 下也可省略，
 * 引入 nacos-discovery 依赖后会自动装配，这里保留注解便于学习者识别）。
 */
@EnableDiscoveryClient
@SpringBootApplication
public class UserServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserServiceApplication.class, args);
    }
}
