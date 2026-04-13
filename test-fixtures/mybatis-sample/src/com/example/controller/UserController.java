package com.example.controller;

import com.example.model.User;
import com.example.service.UserService;

/**
 * REST controller for User resource.
 * Simulates Spring MVC annotations without importing Spring (no build system).
 */
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    // @GetMapping("/{id}")
    public User getUser(Long id) {
        return userService.findById(id);
    }

    // @PostMapping
    public User createUser(String username, String email) {
        return userService.createUser(username, email);
    }

    // @PutMapping("/{id}/email")
    public User updateEmail(Long id, String email) {
        return userService.updateEmail(id, email);
    }

    // @DeleteMapping("/{id}")
    public void deleteUser(Long id) {
        userService.deleteUser(id);
    }
}
