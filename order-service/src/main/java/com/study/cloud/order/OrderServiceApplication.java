package com.study.cloud.order;

import org.mybatis.spring.annotation.MapperScan;
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
 * @MapperScan：扫描 MyBatis-Plus Mapper 接口（Seata 会自动代理其数据源）。
 * scanBasePackages：额外扫描 com.study.cloud.common，加载共享的全局异常处理器；
 *                   @EnableFeignClients / @MapperScan 各有独立扫描根，不受影响。
 */
@EnableFeignClients
@EnableDiscoveryClient
@EnableCaching
@MapperScan("com.study.cloud.order.mapper")
@SpringBootApplication(scanBasePackages = {"com.study.cloud.order", "com.study.cloud.common"})
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
