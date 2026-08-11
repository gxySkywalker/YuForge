package com.yuforge.snapshot;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class SnapshotService implements AutoCloseable {
    private final SideGitManager manager;
    private final ExecutorService executor;
    private volatile Future<?> lastAsyncTask;
    /** 当前 CLI turn 的按需快照上下文；工具可在并行线程中访问。 */
    private final AtomicReference<TurnContext> activeTurn = new AtomicReference<>();

    public SnapshotService(SideGitManager manager) {
        this.manager = manager;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "yuforge-snapshot-writer");
            thread.setDaemon(true);
            return thread;
        });
    }

    public static SnapshotService forProject(Path projectRoot) {
        return new SnapshotService(new SideGitManager(projectRoot));
    }

    public <T> T runTurn(String mode, String input, ThrowingSupplier<T> supplier) throws Exception {
        TurnContext turn = new TurnContext(turnId(mode), summarize(mode, input));
        activeTurn.set(turn);
        try {
            return supplier.get();
        } finally {
            activeTurn.compareAndSet(turn, null);
            if (turn.preSnapshotCreated) {
                snapshotAfterTurnAsync(turn.turnId, turn.summary);
            }
        }
    }

    /**
     * 在本轮第一次可能修改 workspace 前建立 pre-turn 快照。
     *
     * <p>纯聊天和只读探索不会调用此方法，因此不会为无写入轮次扫描整个仓库。
     * 并行工具共享同一个 turn context，实际快照只会创建一次。</p>
     */
    public void ensurePreTurnSnapshot() {
        TurnContext turn = activeTurn.get();
        if (turn == null) {
            // 兼容直接调用 ToolRegistry 的非 CLI 场景：宁可多一次快照，也不能在写入前失去保护。
            snapshotBeforeTurn(turnId("tool"), "mode=tool\\ninput=direct tool invocation");
            return;
        }
        synchronized (turn) {
            if (turn.snapshotAttempted) {
                return;
            }
            turn.snapshotAttempted = true;
            turn.preSnapshotCreated = snapshotBeforeTurn(turn.turnId, turn.summary);
        }
    }

    public boolean snapshotBeforeTurn(String turnId, String summary) {
        if (!manager.config().enabled() || !isWritableWorkspace()) {
            return false;
        }
        try {
            return manager.preTurnSnapshot(turnId, summary) != null;
        } catch (Exception e) {
            System.err.println("[warn] pre-turn 快照失败: " + e.getMessage());
            return false;
        }
    }

    public void snapshotAfterTurnAsync(String turnId, String summary) {
        if (!manager.config().enabled() || !isWritableWorkspace()) {
            return;
        }
        lastAsyncTask = executor.submit(() -> {
            try {
                manager.postTurnSnapshot(turnId, summary);
            } catch (Exception e) {
                System.err.println("[warn] post-turn 快照失败: " + e.getMessage());
            }
        });
    }

    public List<TurnSnapshot> listSnapshots(int limit) throws Exception {
        awaitIdle();
        return manager.listSnapshots(limit);
    }

    public RestoreResult restorePreTurn(int offset) throws Exception {
        awaitIdle();
        return manager.restorePreTurn(offset);
    }

    public String status() {
        return manager.formatStatus();
    }

    public String clean() {
        return manager.cleanSnapshots();
    }

    public SideGitManager manager() {
        return manager;
    }

    public void awaitIdle() throws Exception {
        Future<?> task = lastAsyncTask;
        if (task != null) {
            task.get(60, TimeUnit.SECONDS);
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private boolean isWritableWorkspace() {
        Path workspace = manager.projectRoot();
        return Files.isDirectory(workspace) && Files.isReadable(workspace) && Files.isWritable(workspace);
    }

    private static String turnId(String mode) {
        String safeMode = mode == null || mode.isBlank() ? "turn" : mode.toLowerCase().replaceAll("[^a-z0-9_-]", "-");
        return safeMode + "-" + Instant.now().toEpochMilli();
    }

    private static String summarize(String mode, String input) {
        String normalized = input == null ? "" : input.replaceAll("\\s+", " ").trim();
        if (normalized.length() > 120) {
            normalized = normalized.substring(0, 120) + "...";
        }
        return "mode=" + (mode == null ? "turn" : mode) + "\ninput=" + normalized;
    }

    private static final class TurnContext {
        private final String turnId;
        private final String summary;
        private boolean snapshotAttempted;
        private boolean preSnapshotCreated;

        private TurnContext(String turnId, String summary) {
            this.turnId = turnId;
            this.summary = summary;
        }
    }

    @FunctionalInterface
    public interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
