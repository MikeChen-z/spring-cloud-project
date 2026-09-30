package com.study.cloud.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.study.cloud.user.entity.User;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户数据访问层。
 *
 * 继承 BaseMapper<User> 后，零 SQL 就拥有：
 *   selectById / insert / updateById / deleteById / selectList ...
 * 复杂查询再手写 XML 或 @Select 注解。
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}
