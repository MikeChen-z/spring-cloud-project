package com.study.cloud.user.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.study.cloud.user.entity.User;
import com.study.cloud.user.mapper.UserMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;

/**
 * 用户业务层。
 *
 * 继承 ServiceImpl 自动拥有 getById / save / removeById 等通用方法，
 * 真实项目里在这里写业务校验、组合多表查询等。
 */
@Service
public class UserService extends ServiceImpl<UserMapper, User> {

    /**
     * 登录校验：按用户名查记录，再比对密码。
     *
     * 学习工程使用明文比对；真实项目中库里存的是 BCrypt 哈希，
     * 这里应使用 passwordEncoder.matches(raw, user.getPassword())。
     *
     * 校验失败抛 401，Spring MVC 会把 ResponseStatusException 转成对应状态码响应。
     */
    public User login(String username, String password) {
        User user = lambdaQuery().eq(User::getName, username).one();
        if (user == null || !user.getPassword().equals(password)) {
            // 不区分"用户不存在"和"密码错误"，避免暴露哪些用户名已注册
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }
        return user;
    }

    /**
     * 扣减余额（Seata AT 全局事务的分支事务 = RM）。
     *
     * 这里不加 @GlobalTransactional：XID 由 order-service 通过请求头传过来，
     * Seata 自动把本方法的 SQL 纳入同一个全局事务，
     * 并在本地 undo_log 写快照，由 TC 统一通知提交/回滚。
     *
     * 保留 @Transactional 保证分支内本地事务完整（查校验 + UPDATE 原子执行）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deduct(Long id, BigDecimal amount) {
        User user = getById(id);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "用户不存在：" + id);
        }
        if (user.getBalance() == null || user.getBalance().compareTo(amount) < 0) {
            // 业务失败：异常返回给 order-service 后同样会触发全局回滚
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "余额不足：当前 " + user.getBalance() + "，尝试扣减 " + amount);
        }
        user.setBalance(user.getBalance().subtract(amount));
        updateById(user);
    }
}
