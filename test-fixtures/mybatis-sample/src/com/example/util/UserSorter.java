package com.example.util;

import com.example.model.User;
import java.util.Comparator;

/**
 * Test fixture for DEBT-001 hardening.
 * Exercises nested named class and anonymous class FQN generation in SourceIndexer.
 */
public class UserSorter {

    /** Named static nested class — exercises the nested-class FQN path. */
    public static class ByUsername implements Comparator<User> {
        @Override
        public int compare(User a, User b) {
            return a.getUsername().compareTo(b.getUsername());
        }
    }

    /** Anonymous class — exercises the anonymous-class FQN path. */
    public Comparator<User> byEmail() {
        return new Comparator<User>() {
            @Override
            public int compare(User a, User b) {
                return a.getEmail().compareTo(b.getEmail());
            }
        };
    }
}
