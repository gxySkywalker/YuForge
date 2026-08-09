package com.yuforge.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectMemoryInitializerTest {

    @TempDir
    Path tempDir;

    @Test
    void generatesConciseYuForgeProjectMemory() throws Exception {
        Files.writeString(tempDir.resolve("README.md"), "# YuForge\n\nJava Agent CLI");
        Files.writeString(tempDir.resolve("AGENTS.md"), "项目名：YuForge\n改命令入口要联动");
        Files.writeString(tempDir.resolve("pom.xml"), "<project></project>");

        ProjectMemoryInitializer.InitResult result = ProjectMemoryInitializer.initialize(tempDir, false);

        String content = Files.readString(tempDir.resolve("YUFORGE.md"));
        assertTrue(result.written());
        assertTrue(content.contains("# YUFORGE.md"));
        assertTrue(content.contains("YuForge 是面向商业使用的 Java Agent CLI 产品"));
        assertTrue(content.contains("mvn test -Pquick"));
        assertTrue(content.contains("不要为某个模式创建孤立能力"));
        assertTrue(content.lines().count() < 45, content);
    }

    @Test
    void doesNotOverwriteExistingFileWithoutForce() throws Exception {
        Files.writeString(tempDir.resolve("YUFORGE.md"), "existing");

        ProjectMemoryInitializer.InitResult result = ProjectMemoryInitializer.initialize(tempDir, false);

        assertFalse(result.written());
        assertTrue(Files.readString(tempDir.resolve("YUFORGE.md")).equals("existing"));
    }

    @Test
    void forceOverwritesExistingFile() throws Exception {
        Files.writeString(tempDir.resolve("README.md"), "# YuForge\n");
        Files.writeString(tempDir.resolve("YUFORGE.md"), "existing");

        ProjectMemoryInitializer.InitResult result = ProjectMemoryInitializer.initialize(tempDir, true);

        assertTrue(result.written());
        assertTrue(Files.readString(tempDir.resolve("YUFORGE.md")).contains("# YUFORGE.md"));
    }

    @Test
    void readsBoundedArchitectureFactsForSpringProject() throws Exception {
        Files.writeString(tempDir.resolve("README.md"), "# hm-dianping\n");
        Files.writeString(tempDir.resolve("pom.xml"), "<project>spring-boot mybatis redis</project>");
        Path packageRoot = tempDir.resolve("src/main/java/com/hmdp");
        Files.createDirectories(packageRoot.resolve("controller"));
        Files.createDirectories(packageRoot.resolve("service"));
        Files.createDirectories(packageRoot.resolve("mapper"));
        Files.writeString(packageRoot.resolve("HmDianPingApplication.java"), "class HmDianPingApplication {}");
        Path resources = tempDir.resolve("src/main/resources");
        Files.createDirectories(resources);
        Files.writeString(resources.resolve("application.yaml"), "server: {}\n");

        String content = ProjectMemoryInitializer.generate(tempDir);

        assertTrue(content.contains("src/main/java/com/hmdp"), content);
        assertTrue(content.contains("controller"), content);
        assertTrue(content.contains("HmDianPingApplication.java"), content);
        assertTrue(content.contains("src/main/resources/application.yaml"), content);
        assertTrue(content.contains("Spring Boot + MyBatis + Redis"), content);
        assertTrue(content.contains("代码规模：`src/main/java` 1 个 Java 文件"), content);
    }
}
