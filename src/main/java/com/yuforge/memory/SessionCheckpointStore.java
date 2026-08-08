package com.yuforge.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.yuforge.llm.LlmClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** 会话恢复用的有界、可审计 checkpoint 存储。不会持久化图片或 artifact 原文。 */
public final class SessionCheckpointStore {
    private static final String DIR_PROPERTY = "yuforge.session.dir";
    private static final String DIR_ENV = "YUFORGE_SESSION_DIR";
    private static final int MAX_CHECKPOINTS = 30;
    private final Path directory;
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public SessionCheckpointStore() { this(resolveDirectory()); }
    SessionCheckpointStore(Path directory) { this.directory = directory.toAbsolutePath().normalize(); }

    public synchronized Checkpoint save(String projectKey, String model, List<LlmClient.Message> history, String todoJson) throws IOException {
        Files.createDirectories(directory);
        String id = "session_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        List<StoredMessage> messages = new ArrayList<>();
        for (LlmClient.Message message : history == null ? List.<LlmClient.Message>of() : history) {
            if (message == null || "system".equals(message.role())) continue;
            String content = "tool".equals(message.role())
                    ? "[恢复会话时未持久化工具结果原文；如需该信息，请重新执行受控工具或读取相关文件。]"
                    : sanitizeContent(message.content());
            messages.add(new StoredMessage(message.role(), content, message.reasoningContent(), message.toolCalls(), message.toolCallId()));
        }
        Checkpoint checkpoint = new Checkpoint(1, id, projectKey, model, java.time.Instant.now().toString(), messages, todoJson == null ? "" : todoJson);
        Path target = pathOf(id);
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        mapper.writeValue(temporary.toFile(), checkpoint);
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
        trimOldCheckpoints();
        return checkpoint;
    }

    public synchronized Checkpoint load(String id) throws IOException {
        if (id == null || id.isBlank() || !id.matches("session_[a-zA-Z0-9]+")) throw new IOException("非法 session id");
        Path path = pathOf(id.trim());
        if (!Files.isRegularFile(path)) throw new IOException("未找到 session: " + id.trim());
        return mapper.readValue(path.toFile(), Checkpoint.class);
    }

    public synchronized List<CheckpointSummary> list(int limit) throws IOException {
        if (!Files.isDirectory(directory)) return List.of();
        try (var paths = Files.list(directory)) {
            return paths.filter(path -> path.getFileName().toString().startsWith("session_") && path.toString().endsWith(".json"))
                    .map(path -> readSummary(path))
                    .filter(summary -> summary != null)
                    .sorted(Comparator.comparing(CheckpointSummary::createdAt).reversed())
                    .limit(Math.max(1, limit)).toList();
        }
    }

    private CheckpointSummary readSummary(Path path) {
        try {
            Checkpoint checkpoint = mapper.readValue(path.toFile(), Checkpoint.class);
            return new CheckpointSummary(checkpoint.id(), checkpoint.projectKey(), checkpoint.model(), checkpoint.createdAt(), checkpoint.messages().size());
        } catch (IOException ignored) { return null; }
    }

    private void trimOldCheckpoints() throws IOException {
        List<CheckpointSummary> summaries = list(MAX_CHECKPOINTS + 1);
        for (int index = MAX_CHECKPOINTS; index < summaries.size(); index++) Files.deleteIfExists(pathOf(summaries.get(index).id()));
    }

    private Path pathOf(String id) { return directory.resolve(id + ".json"); }
    private static String sanitizeContent(String content) {
        String value = content == null ? "" : content;
        if (!value.startsWith("[旧工具结果已归档]")) return value;
        String artifactId = value.lines().filter(line -> line.startsWith("artifact_id:"))
                .findFirst().orElse("artifact_id: unavailable");
        return "[恢复会话时未持久化旧工具原文；artifact_id 仅供审计，不可恢复。]\n" + artifactId;
    }
    private static Path resolveDirectory() {
        String configured = System.getProperty(DIR_PROPERTY);
        if (configured == null || configured.isBlank()) configured = System.getenv(DIR_ENV);
        return configured == null || configured.isBlank()
                ? Path.of(System.getProperty("user.home"), ".yuforge", "sessions") : Path.of(configured);
    }

    public record StoredMessage(String role, String content, String reasoningContent, List<LlmClient.ToolCall> toolCalls, String toolCallId) {
        public LlmClient.Message toMessage() { return new LlmClient.Message(role, content, reasoningContent, toolCalls, toolCallId); }
    }
    public record Checkpoint(int version, String id, String projectKey, String model, String createdAt,
                             List<StoredMessage> messages, String todoJson) { }
    public record CheckpointSummary(String id, String projectKey, String model, String createdAt, int messageCount) { }
}
