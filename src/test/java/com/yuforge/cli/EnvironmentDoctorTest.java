package com.yuforge.cli;

import com.yuforge.config.YuForgeConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvironmentDoctorTest {

    @Test
    void reportsReadOnlyEnvironmentFacts(@TempDir Path workspace) {
        YuForgeConfig config = new YuForgeConfig();
        String report = EnvironmentDoctor.report(workspace, config, "fixture_missing_provider", "fixture-model", "0/0 个 server 就绪");

        assertTrue(report.contains("YuForge 环境诊断"));
        assertTrue(report.contains("当前模型: fixture-model (fixture_missing_provider)"));
        assertTrue(report.contains("fixture_missing_provider 未配置"));
        assertTrue(report.contains("不验证 API 连通性"));
    }
}
