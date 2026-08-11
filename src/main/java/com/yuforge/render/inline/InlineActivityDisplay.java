package com.yuforge.render.inline;

import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;

import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Fixed-height transient activity area for model thinking.
 *
 * <p>The live area only ever clears and rewrites rows that it printed itself.
 * It intentionally avoids {@code Display.update(...)} because an independent
 * JLine Display does not share layout ownership with the transcript and status
 * renderer; once scrollback moves, Display can clear from the wrong origin.
 */
final class InlineActivityDisplay implements AutoCloseable {

    private static final String[] SPINNER_FRAMES = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
    private static final AttributedStyle STATUS_STYLE = AttributedStyle.DEFAULT.italic();

    private final Terminal terminal;
    private final PrintStream renderLock;
    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?> tickTask;
    private boolean active;
    private boolean closed;
    private String label = "Thinking";
    private boolean showCancelHint = true;
    private long startedNanos;
    private int frame;
    private int renderedRows;

    InlineActivityDisplay(Terminal terminal, PrintStream renderLock) {
        this(terminal, renderLock, null);
    }

    InlineActivityDisplay(Terminal terminal, PrintStream renderLock, BottomStatusBar statusBar) {
        this.terminal = terminal;
        this.renderLock = renderLock;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "yuforge-activity-display");
            t.setDaemon(true);
            return t;
        });
    }

    /** thinking 面板是否正在显示——给 InlineRenderer 决定是否在 status 更新时触发重绘。 */
    synchronized boolean isActive() {
        return active && !closed;
    }

    /** 当 renderer 状态变化时，如果 thinking 正在显示，则刷新 spinner 和计时。 */
    synchronized void refreshIfActive() {
        if (active && !closed) {
            renderLocked();
        }
    }

    synchronized void begin(String label) {
        if (closed) {
            return;
        }
        clearLocked();
        this.label = (label == null || label.isBlank()) ? "Thinking" : label.trim();
        this.showCancelHint = true;
        this.startedNanos = System.nanoTime();
        this.frame = 0;
        this.active = true;
        renderLocked();
        restartTickLocked();
    }

    synchronized void beginActivity(String label, String detail) {
        if (closed) {
            return;
        }
        clearLocked();
        this.label = (label == null || label.isBlank()) ? "Working" : label.trim();
        this.showCancelHint = false;
        this.startedNanos = System.nanoTime();
        this.frame = 0;
        this.active = true;
        renderLocked();
        restartTickLocked();
    }

    synchronized void updateActivity(String label, String detail) {
        if (!active || closed) {
            return;
        }
        this.label = (label == null || label.isBlank()) ? this.label : label.trim();
        renderLocked();
    }

    /**
     * 暂时移除活动行，让紧邻它上方的最近折叠块安全原地切换，随后恢复同一计时活动行。
     * startedNanos 不变，因此展开详情不会让用户失去“仍在执行”的时间感知。
     */
    synchronized boolean whileHidden(BooleanSupplier action) {
        if (closed) {
            return action.getAsBoolean();
        }
        boolean wasActive = active;
        if (wasActive) {
            cancelTickLocked();
            clearLocked();
            active = false;
        }
        boolean changed;
        try {
            changed = action.getAsBoolean();
        } finally {
            if (wasActive && !closed) {
                active = true;
                renderLocked();
                restartTickLocked();
            }
        }
        return changed;
    }

    synchronized void appendThinking(String delta) {
        // 默认不在瞬态区域展示原始 reasoning。保持固定单行是 resize 安全边界；
        // reasoning 仍按 provider 协议保留在历史/日志中。
    }

    synchronized void end() {
        if (closed) {
            return;
        }
        active = false;
        cancelTickLocked();
        clearLocked();
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        active = false;
        cancelTickLocked();
        clearLocked();
        scheduler.shutdownNow();
    }

    private void restartTickLocked() {
        cancelTickLocked();
        tickTask = scheduler.scheduleAtFixedRate(this::tick, 250, 250, TimeUnit.MILLISECONDS);
    }

    private void cancelTickLocked() {
        if (tickTask != null) {
            tickTask.cancel(false);
            tickTask = null;
        }
    }

    private void tick() {
        synchronized (this) {
            if (!active || closed) {
                return;
            }
            frame++;
            renderLocked();
        }
    }

    private void renderLocked() {
        if (!active || closed) {
            return;
        }
        synchronized (renderLock) {
            PrintWriter writer = terminalWriter();
            clearRenderedArea(writer);
            List<AttributedString> lines = buildLines();
            for (int i = 0; i < lines.size(); i++) {
                writer.print(lines.get(i).toAnsi(terminal));
                writer.print(AnsiSeq.CLEAR_TO_EOL);
                if (i < lines.size() - 1) {
                    writer.print('\n');
                }
            }
            renderedRows = lines.size();
            writer.flush();
            terminal.flush();
        }
    }

    private void clearLocked() {
        synchronized (renderLock) {
            PrintWriter writer = terminalWriter();
            clearRenderedArea(writer);
            writer.flush();
            terminal.flush();
        }
    }

    private PrintWriter terminalWriter() {
        PrintWriter writer = terminal.writer();
        if (writer != null) {
            return writer;
        }
        return new PrintWriter(renderLock, true, StandardCharsets.UTF_8);
    }

    private void clearRenderedArea(PrintWriter writer) {
        if (renderedRows <= 0) {
            return;
        }
        writer.print('\r');
        writer.print(AnsiSeq.CLEAR_LINE);
        renderedRows = 0;
    }

    private List<AttributedString> buildLines() {
        int cols = Math.max(20, TerminalCapabilities.safeSize(terminal).getColumns() - 1);
        List<AttributedString> lines = new ArrayList<>();
        if (!showCancelHint) {
            lines.add(fit("  " + spinner() + " " + label + "... " + elapsedSeconds() + "s", cols, STATUS_STYLE));
            return lines;
        }
        String suffix = " (Esc cancel, " + elapsedSeconds() + "s)";
        lines.add(fit("  " + spinner() + " " + label + "..." + suffix, cols, STATUS_STYLE));
        return lines;
    }

    private AttributedString fit(String text, int cols, AttributedStyle style) {
        AttributedString attributed = new AttributedString(text == null ? "" : text, style);
        if (attributed.columnLength(terminal) <= cols) {
            return attributed;
        }
        if (cols <= 3) {
            return new AttributedString(".".repeat(Math.max(0, cols)), style);
        }
        return new AttributedString(
                attributed.columnSubSequence(0, cols - 3, terminal).toString() + "...",
                style);
    }

    private String spinner() {
        return SPINNER_FRAMES[Math.floorMod(frame, SPINNER_FRAMES.length)];
    }


    private long elapsedSeconds() {
        return Math.max(0, TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startedNanos));
    }

}
