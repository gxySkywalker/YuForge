package com.yuforge.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReasoningDisplayPolicyTest {

    @Test
    void rawReasoningIsHiddenByDefaultAndCanBeEnabledForDebugging() {
        String previous = System.getProperty(ReasoningDisplayPolicy.SYSTEM_PROPERTY);
        try {
            System.setProperty(ReasoningDisplayPolicy.SYSTEM_PROPERTY, "false");
            assertFalse(ReasoningDisplayPolicy.showRawReasoning());

            System.setProperty(ReasoningDisplayPolicy.SYSTEM_PROPERTY, "true");
            assertTrue(ReasoningDisplayPolicy.showRawReasoning());
        } finally {
            if (previous == null) {
                System.clearProperty(ReasoningDisplayPolicy.SYSTEM_PROPERTY);
            } else {
                System.setProperty(ReasoningDisplayPolicy.SYSTEM_PROPERTY, previous);
            }
        }
    }
}
