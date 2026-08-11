package com.yuforge.rag;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CodeIndexTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void clearRagDirectoryOverride() {
        System.clearProperty("yuforge.rag.dir");
    }

    @Test
    void testIndexNonExistentPath() {
        CodeIndex indexer = new CodeIndex();
        CodeIndex.IndexResult result = indexer.index("/non/existent/path");
        assertEquals(0, result.chunkCount());
        assertTrue(result.message().contains("路径不存在"));
    }

    @Test
    void testIndexCurrentProject() {
        System.setProperty("yuforge.rag.dir", tempDir.resolve("index").toString());
        CodeIndex indexer = new CodeIndex(offlineEmbeddingClient());
        // 索引测试资源目录
        CodeIndex.IndexResult result = indexer.index("src/test/resources/rag");
        assertTrue(result.chunkCount() > 0, "应该至少索引一个代码块");
        assertTrue(result.message().contains("索引完成"));
    }

    @Test
    void reportsProgressThroughListener() {
        System.setProperty("yuforge.rag.dir", tempDir.resolve("progress").toString());
        List<String> messages = new ArrayList<>();
        CodeIndex indexer = new CodeIndex(offlineEmbeddingClient(), messages::add);

        CodeIndex.IndexResult result = indexer.index("src/test/resources/rag");

        assertTrue(result.chunkCount() > 0, "应该至少索引一个代码块");
        assertTrue(messages.stream().anyMatch(message -> message.startsWith("开始索引")));
        assertTrue(messages.stream().anyMatch(message -> message.startsWith("📁 发现")));
        assertTrue(messages.stream().anyMatch(message -> message.startsWith("✅ 索引完成")));
    }

    private static EmbeddingClient offlineEmbeddingClient() {
        return new EmbeddingClient("ollama", "stub", "http://localhost", "") {
            @Override
            public float[] embed(String text) {
                return new float[]{text == null ? 0.0f : text.length(), 1.0f};
            }
        };
    }
}
