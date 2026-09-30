package com.study.cloud.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 订单服务启动类（服务消费者）。
 *
 * @EnableFeignClients：扫描 @FeignClient 接口并生成代理实现，
 *                      调用方法时实际发起 HTTP 请求。
 * @EnableCaching：开启 Spring Cache，让 @Cacheable 注解生效。
 */
@EnableFeignClients
@EnableDiscoveryClient
@EnableCaching
@SpringBootApplication
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
