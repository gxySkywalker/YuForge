package com.yuforge.prompt;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeContextFormatterTest {

    @Test
    void prependsDeterministicEnvironmentAndKeepsUserRequestLast() {
        RuntimeContextFormatter formatter = new RuntimeContextFormatter(
                Clock.fixed(Instant.parse("2026-08-07T12:30:45Z"), ZoneId.of("Asia/Shanghai")));

        String content = formatter.prepend(
                "修复登录测试",
                Path.of("demo"),
                "bash",
                "turn_id: 7\nphase: running",
                "MCP resource index");

        assertTrue(content.contains("<timestamp>2026-08-07T20:30:45+08:00</timestamp>"));
        assertTrue(content.contains("<current_date>2026-08-07</current_date>"));
        assertTrue(content.contains("<timezone>Asia/Shanghai</timezone>"));
        assertTrue(content.contains("<shell>bash</shell>"));
        assertTrue(content.contains("turn_id: 7"));
        assertTrue(content.indexOf("<dynamic_context>") < content.indexOf("<user_request>"));
        assertTrue(content.endsWith("修复登录测试\n</user_request>"));
    }
}
