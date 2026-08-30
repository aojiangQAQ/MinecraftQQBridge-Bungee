package h_aaa.mcqqbridge.service;

import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminPolicyTest {
    @Test
    void requiresExplicitConfiguredIdForDangerousOperations() {
        AdminPolicy policy = new AdminPolicy(
                Collections.singleton("123456"), false, false, false);

        assertTrue(policy.isAdministrator("123456", "member"));
        assertFalse(policy.isAdministrator("999999", "owner"));
        assertFalse(policy.canQueryOther("999999", "owner"));
    }

    @Test
    void optionallyRequiresStructuredGroupRole() {
        AdminPolicy policy = new AdminPolicy(
                Collections.singleton("123456"), true, false, false);

        assertFalse(policy.isAdministrator("123456", "member"));
        assertTrue(policy.isAdministrator("123456", "admin"));
        assertTrue(policy.isAdministrator("123456", "owner"));
    }
}
