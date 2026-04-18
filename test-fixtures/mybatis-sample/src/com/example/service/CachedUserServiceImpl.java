package com.example.service;

import com.example.model.User;
import java.util.HashMap;
import java.util.Map;

/**
 * In-memory cache implementation of UserService — intentionally makes no calls
 * to UserMapper, so it does not appear in call chains that originate from
 * UserMapper.  Used by Feature 2.3r1 tests to verify that the hierarchy-aware
 * dispatch query returns BOTH UserService implementations (not just the one that
 * has outgoing edges, as the old heuristic did).
 */
public class CachedUserServiceImpl implements UserService {

    private final Map<Long, User> cache = new HashMap<>();

    @Override
    public User findById(Long id) {
        return cache.get(id);
    }

    @Override
    public User createUser(String username, String email) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        cache.put(user.getId(), user);
        return user;
    }

    @Override
    public User updateEmail(Long id, String newEmail) {
        User user = cache.get(id);
        if (user != null) {
            user.setEmail(newEmail);
        }
        return user;
    }

    @Override
    public void deleteUser(Long id) {
        cache.remove(id);
    }
}
