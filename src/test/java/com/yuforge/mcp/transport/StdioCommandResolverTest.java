package com.yuforge.mcp.transport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StdioCommandResolverTest {

    @Test
    void resolvesCmdLauncherOnWindows(@TempDir Path tempDir) throws Exception {
        Path launcher = tempDir.resolve("npx.cmd");
        Files.writeString(launcher, "@echo off");

        assertEquals(launcher.toAbsolutePath().normalize().toString(),
                StdioTransport.resolveCommandForProcess("npx", true, tempDir.toString()));
    }

    @Test
    void preservesExplicitCommandExtensions(@TempDir Path tempDir) {
        assertEquals("npx.cmd", StdioTransport.resolveCommandForProcess("npx.cmd", true, tempDir.toString()));
        assertEquals("npx", StdioTransport.resolveCommandForProcess("npx", false, tempDir.toString()));
    }
}
