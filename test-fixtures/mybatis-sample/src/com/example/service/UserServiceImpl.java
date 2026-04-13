package com.example.service;

import com.example.mapper.UserMapper;
import com.example.model.User;

public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;

    public UserServiceImpl(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public User findById(Long id) {
        return userMapper.selectById(id);
    }

    @Override
    public User createUser(String username, String email) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        userMapper.insert(user);
        return userMapper.selectById(user.getId());
    }

    @Override
    public User updateEmail(Long id, String newEmail) {
        User user = userMapper.selectById(id);
        user.setEmail(newEmail);
        userMapper.update(user);
        return user;
    }

    @Override
    public void deleteUser(Long id) {
        userMapper.deleteById(id);
    }
}
