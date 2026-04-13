package com.example.service;

import com.example.model.User;

public interface UserService {

    User findById(Long id);

    User createUser(String username, String email);

    User updateEmail(Long id, String newEmail);

    void deleteUser(Long id);
}
