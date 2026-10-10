package com.mypj.aipassagecreator.mapper;

import com.mybatisflex.core.BaseMapper;
import com.mypj.aipassagecreator.model.entity.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<User> {
}

