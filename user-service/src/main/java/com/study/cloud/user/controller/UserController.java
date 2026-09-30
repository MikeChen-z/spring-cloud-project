package com.study.cloud.user.controller;

import com.study.cloud.user.entity.User;
import com.study.cloud.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户接口（被 order-service 通过 OpenFeign 远程调用）。
 * 数据来源从内存 Map 改成 MySQL（通过 MyBatis-Plus）。
 */
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/{id}")
    public User getById(@PathVariable Long id) {
        // getById 由 ServiceImpl 提供，底层是 SELECT * FROM t_user WHERE id = ?
        User user = userService.getById(id);
        if (user == null) {
            // 查不到时返回一个带 id 的占位对象，不抛 404（方便下游做降级演示）
            user = new User();
            user.setId(id);
            user.setName("用户不存在");
            user.setEmail("N/A");
        }
        // 非数据库字段，标识来源服务
        user.setSource("user-service:8081");
        return user;
    }
}
