package com.study.cloud.user.controller;

import com.study.cloud.common.result.Result;
import com.study.cloud.user.entity.User;
import com.study.cloud.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * 用户接口（被 order-service 通过 OpenFeign 远程调用）。
 * 数据来源从内存 Map 改成 MySQL（通过 MyBatis-Plus）。
 *
 * 统一返回 {@link Result}：Feign 也是 HTTP 调用，消费方拿到的同样是
 * {code,message,data} 结构，需要判断 code 后再取 data。
 */
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/{id}")
    public Result<User> getById(@PathVariable Long id) {
        // getById 由 ServiceImpl 提供，底层是 SELECT * FROM t_user WHERE id = ?
        User user = userService.getById(id);
        if (user == null) {
            // 查不到时返回一个带 id 的占位对象，不抛错误（方便下游做降级演示）
            user = new User();
            user.setId(id);
            user.setName("用户不存在");
            user.setEmail("N/A");
        }
        // 非数据库字段，标识来源服务
        user.setSource("user-service:8081");
        return Result.success(user);
    }

    /**
     * 扣减余额：Seata 全局事务的扣款分支（RM）。
     * XID 由 Seata 经 HTTP 请求头自动传入，无需在此处理。
     * 余额不足/用户不存在时抛 BusinessException（HTTP 400），
     * Feign 收到 4xx 抛错，经 fallback 继续抛出后触发全局回滚。
     */
    @PostMapping("/{id}/deduct")
    public Result<Void> deduct(@PathVariable Long id, @RequestParam BigDecimal amount) {
        userService.deduct(id, amount);
        return Result.success();
    }
}
