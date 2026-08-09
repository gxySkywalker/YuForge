package com.yuforge.policy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceTrustStoreTest {

    @Test
    void shouldPersistExactTrustedWorkspaceOnly(@TempDir Path tempDir) throws Exception {
        Path trustedWorkspace = tempDir.resolve("project");
        Path childWorkspace = trustedWorkspace.resolve("nested");
        java.nio.file.Files.createDirectories(childWorkspace);
        WorkspaceTrustStore store = new WorkspaceTrustStore(tempDir.resolve("state/trusted.txt"));

        assertFalse(store.isTrusted(trustedWorkspace));
        store.trust(trustedWorkspace);

        assertTrue(store.isTrusted(trustedWorkspace));
        assertFalse(store.isTrusted(childWorkspace));
        assertTrue(new WorkspaceTrustStore(store.storeFile()).isTrusted(trustedWorkspace));
    }
}
