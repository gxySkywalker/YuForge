package com.yuforge.runtime.process;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedProcessManagerTest {

    @Test
    void startsCapturesLogAndListsProcess(@TempDir Path workspace) throws Exception {
        try (ManagedProcessManager manager = new ManagedProcessManager(workspace)) {
            ManagedProcessManager.ProcessInfo process = manager.start("java -version");

            for (int i = 0; i < 40 && "running".equals(process.status()); i++) {
                Thread.sleep(50);
                process = manager.list().get(0);
            }

            assertEquals(1, manager.list().size());
            assertTrue(process.logPath().startsWith(".yuforge/processes/"), process.logPath());
            assertTrue(manager.tail(process.id(), 4_000).toLowerCase().contains("version"));
        }
    }

    @Test
    void rejectsUnknownProcessId(@TempDir Path workspace) {
        try (ManagedProcessManager manager = new ManagedProcessManager(workspace)) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> manager.stop("proc_missing"));
            assertTrue(error.getMessage().contains("未找到"));
        }
    }

    @Test
    void detectsReadyEndpointFromDevServerLog(@TempDir Path workspace) throws Exception {
        try (ManagedProcessManager manager = new ManagedProcessManager(workspace)) {
            String command = System.getProperty("os.name", "").toLowerCase().contains("win")
                    ? "Write-Output 'VITE v5.0.0 ready in 100 ms'; Write-Output 'Local: http://localhost:5173'"
                    : "printf 'VITE v5.0.0 ready in 100 ms\\nLocal: http://localhost:5173\\n'";
            ManagedProcessManager.ProcessInfo process = manager.start(command);
            ManagedProcessManager.ReadinessInfo readiness = manager.waitForReadiness(process.id(), 3);

            assertEquals("ready", readiness.status());
            assertEquals("http://localhost:5173", readiness.endpoint());
        }
    }

    @Test
    void rejectsGuardedCommand(@TempDir Path workspace) {
        try (ManagedProcessManager manager = new ManagedProcessManager(workspace)) {
            assertThrows(RuntimeException.class, () -> manager.start("shutdown now"));
        }
    }
}
