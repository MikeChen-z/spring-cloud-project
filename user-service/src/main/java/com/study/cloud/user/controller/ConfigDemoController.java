package com.study.cloud.user.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Nacos 配置中心 + 动态刷新演示。
 */
@RefreshScope
@RestController
@RequestMapping("/config-demo")
public class ConfigDemoController {

    /**
     * 冒号后面是 Nacos 中还没有该配置时使用的默认值。
     * 在 Nacos 控制台修改 user.greeting 并发布后，本 Bean 会被自动重建，
     * 不重启服务再次访问该接口即可看到新值。
     */
    @Value("${user.greeting:本地默认问候（Nacos 中还没有 user.greeting 配置）}")
    private String greeting;

    @GetMapping
    public Map<String, String> greeting() {
        return Map.of("greeting", greeting);
    }
}
