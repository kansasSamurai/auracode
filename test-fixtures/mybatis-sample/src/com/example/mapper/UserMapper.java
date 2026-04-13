package com.example.mapper;

import com.example.model.User;

/**
 * MyBatis mapper interface for User persistence.
 * SQL is defined in resources/mapper/UserMapper.xml (parsed in Phase 3.1).
 */
public interface UserMapper {

    User selectById(Long id);

    int insert(User user);

    int update(User user);

    int deleteById(Long id);
}
