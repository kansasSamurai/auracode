package com.sourcelens.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RenderCommandTest {

    // -------------------------------------------------------------------------
    // toParticipant
    // -------------------------------------------------------------------------

    @Test
    void toParticipant_topLevelClass_returnsSimpleName() {
        assertEquals("UserController",
                RenderCommand.toParticipant(
                        "com.example.controller.UserController#getUser(Long)"));
    }

    @Test
    void toParticipant_nestedClass_replacesDollarWithUnderscore() {
        assertEquals("UserSorter_ByUsername",
                RenderCommand.toParticipant(
                        "com.example.util.UserSorter$ByUsername#compare(User, User)"));
    }

    @Test
    void toParticipant_anonymousClass_replacesSpecialChars() {
        assertEquals("UserSorter_anonymous_22",
                RenderCommand.toParticipant(
                        "com.example.util.UserSorter$anonymous:22#compare(User, User)"));
    }

    // -------------------------------------------------------------------------
    // toMessage
    // -------------------------------------------------------------------------

    @Test
    void toMessage_standardFqn_returnsMethodSignature() {
        assertEquals("findById(Long)",
                RenderCommand.toMessage(
                        "com.example.service.UserServiceImpl#findById(Long)"));
    }

    // -------------------------------------------------------------------------
    // sanitize
    // -------------------------------------------------------------------------

    @Test
    void sanitize_dollarAndColon_replacedWithUnderscore() {
        assertEquals("Foo_Bar_22", RenderCommand.sanitize("Foo$Bar:22"));
    }
}
