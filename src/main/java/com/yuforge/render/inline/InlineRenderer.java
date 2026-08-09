package com.yuforge.render.inline;

import com.yuforge.hitl.ApprovalRequest;
import com.yuforge.hitl.ApprovalResult;
import com.yuforge.llm.LlmClient;
import com.yuforge.render.PlainRenderer;
import com.yuforge.render.ReasoningDisplayPolicy;
import com.yuforge.render.Renderer;
import com.yuforge.render.StatusInfo;
import com.yuforge.util.AnsiStyle;
import org.jline.reader.LineReader;
import org.jline.reader.Widget;
import org.jline.terminal.Terminal;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Inline 流式渲染器：默认形态。
 *
 * <p>不进 alternate screen，主屏直接输出；输入期状态区紧跟当前 prompt 渲染。
 * 工具调用块、行内 diff、HITL 单字符提示、palette 等高级特性在 Day 3 / Day 4 落地，
 * 现阶段对应方法委托给 {@link PlainRenderer} 兜底。
 */
public final class InlineRenderer implements Renderer {

    private final Terminal terminal;
    private final PlainRenderer fallback;
    private final BottomStatusBar statusBar;
    private final BlockRegistry blockRegistry;
    private final PrintStream stream;
    private final PrintStream out;
    private final Object transcriptLock = new Object();
    private final InlineActivityDisplay activityDisplay;
    private final List<TranscriptEntry> transcript = new ArrayList<>();
    private final AtomicBoolean startupScreenPrinted = new AtomicBoolean(true);
    private volatile LineReader lineReader;
    private volatile boolean started;
    private volatile boolean closed;
    private volatile boolean simpleThinkingVisible;

    // —— 代码块折叠状态机字段（仅供 createTranscriptStream 内部使用）——
    private final StringBuilder lineBuffer = new StringBuilder();
    private final List<String> codeBodyLines = new ArrayList<>();
    private boolean inCodeBlock;
    private String codeLanguage = "";
    private String codeHeaderLine;

    public InlineRenderer(Terminal terminal) {
        this(terminal, System.out);
    }

    /** 测试用构造器：注入输出流，避免污染真实 stdout。 */
    InlineRenderer(Terminal terminal, PrintStream out) {
        this.terminal = terminal;
        this.fallback = new PlainRenderer();
        this.out = out;
        // Windows Terminal 在缩放、全屏和标签恢复时会重排普通滚屏；JLine Status 的底部保留区会留下残影。
        // 默认使用 Codex 风格的普通 composer，底部 dock 仅作为显式实验开关保留。
        this.statusBar = bottomDockEnabled() && TerminalCapabilities.supportsScrollRegion(terminal)
                ? new BottomStatusBar(terminal, out)
                : null;
        // 普通滚屏不维护 live area：它需要回退光标清理旧帧，在 resize、CJK 换行和异步
        // 输出同时发生时无法可靠知道物理行。实验性 dock 也不改变这个默认约束。
        this.activityDisplay = null;
        this.blockRegistry = new BlockRegistry();
        this.stream = createTranscriptStream(out);
    }

    @Override
    public void beginTurn() {
        synchronized (transcriptLock) {
            transcript.clear();
            lineBuffer.setLength(0);
            inCodeBlock = false;
            codeBodyLines.clear();
            codeLanguage = "";
            codeHeaderLine = null;
        }
        blockRegistry.clear();
    }

    @Override
    public void start() {
        if (started || closed) {
            return;
        }
        if (statusBar != null) {
            statusBar.start();
        }
        started = true;
    }

    @Override
    public void beforeInput() {
        if (statusBar != null) {
            statusBar.prepareInputLine();
            statusBar.flushNow();
        }
    }

    @Override
    public void afterInput() {
        if (statusBar != null) {
            statusBar.finishInputLine();
        }
    }

    @Override
    public String inputPrompt() {
        return "> ";
    }

    @Override
    public String inputRightPrompt() {
        // JLine right prompt 是独立浮层，Windows Terminal resize 后会留下旧列宽残影；
        // 主输入保持为稳定的 Codex 式单行 composer，提示写入首屏即可。
        return null;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (activityDisplay != null) {
            activityDisplay.close();
        }
        if (statusBar != null) {
            statusBar.close();
        }
        fallback.close();
    }

    @Override
    public PrintStream stream() {
        return stream;
    }

    @Override
    public int terminalColumns() {
        return Math.max(40, TerminalCapabilities.safeSize(terminal).getColumns());
    }

    @Override
    public boolean supportsThinkingPanel() {
        // 普通滚屏模式仍保留显式 Thinking 状态，只是不使用 resize 时会残影的 live dock。
        return true;
    }

    @Override
    public boolean rendersReasoning() {
        return ReasoningDisplayPolicy.showRawReasoning();
    }

    @Override
    public void beginThinking(String label) {
        if (statusBar != null) {
            statusBar.beginActivityTimer(label);
        }
        if (activityDisplay != null && !closed) {
            activityDisplay.begin(label);
        } else if (!closed && !simpleThinkingVisible) {
            // 进入同一条 transcript，保证它与首个正文/工具卡片严格有序。
            stream.println("· " + (label == null || label.isBlank() ? "Thinking" : label) + "…");
            simpleThinkingVisible = true;
        }
    }

    @Override
    public void appendThinking(String delta) {
        if (activityDisplay != null && !closed) {
            activityDisplay.appendThinking(delta);
        }
    }

    @Override
    public void endThinking() {
        if (statusBar != null) {
            statusBar.endActivityTimer();
        }
        if (activityDisplay != null) {
            activityDisplay.end();
        } else if (simpleThinkingVisible) {
            // 普通滚屏只追加、从不回退清除 activity 行；正文会自然出现在其后。
            simpleThinkingVisible = false;
        }
    }

    @Override
    public boolean supportsActivityPanel() {
        return activityDisplay != null;
    }

    @Override
    public void beginActivity(String label, String detail) {
        if (activityDisplay != null && !closed) {
            activityDisplay.beginActivity(label, detail);
        }
    }

    @Override
    public void endActivity() {
        if (activityDisplay != null) {
            activityDisplay.end();
        }
    }

    /**
     * 绑定当前交互循环使用的 JLine LineReader。
     *
     * <p>绑定后，用户正在输入时的异步输出会优先通过
     * {@link LineReader#printAbove(String)} 显示在输入行上方；非读取态和
     * 测试/降级路径继续使用构造时注入的 {@link PrintStream}。
     */
    public void bindLineReader(LineReader lineReader) {
        this.lineReader = lineReader;
    }

    /** 供 CLI 的终端尺寸变化回调调用，避免底部 Status 按旧屏幕尺寸残留。 */
    public void refreshAfterTerminalResize() {
        if (statusBar != null) {
            statusBar.refreshAfterTerminalResize();
        }
        LineReader reader = lineReader;
        if (reader != null && reader.isReading()) {
            reader.callWidget(LineReader.REDRAW_LINE);
        }
    }

    private static boolean bottomDockEnabled() {
        String property = System.getProperty("yuforge.inline.bottom-dock");
        if (property != null && !property.isBlank()) {
            return Boolean.parseBoolean(property);
        }
        String environment = System.getenv("YUFORGE_INLINE_BOTTOM_DOCK");
        // Windows Terminal 会在缩放、全屏和标签恢复时重排普通 scrollback；即便是 JLine
        // 托管的单行 Status 也可能残留旧帧。默认只使用 append-only transcript。
        return environment != null && Boolean.parseBoolean(environment);
    }

    /**
     * 在 LineReader 第一次进入 readLine 时打印首屏。
     *
     * <p>首屏不能在 readLine 之前用普通 stdout 打印：底部 Status 初始化后，LineReader
     * 第一次重绘会重新接管输入区，提前打印的 banner 容易被滚动或覆盖。挂到
     * {@link LineReader#CALLBACK_INIT} 后，JLine 会把首屏、输入行和底部 dock 放在同一个
     * 显示生命周期里处理。
     */
    public void installStartupScreen(List<String> lines) {
        installStartupScreen(lines, null);
    }

    /**
     * 安装首屏，并在首屏实际写入后触发一个一次性的后台初始化动作。
     *
     * <p>用于 MCP 这类不影响首个输入框可用性的慢初始化：先让用户看到稳定的
     * Banner/composer，再启动后台连接，避免启动进度抢在首屏之前写入普通滚屏。
     */
    public void installStartupScreen(List<String> lines, Runnable afterStartupScreen) {
        LineReader reader = lineReader;
        if (reader == null || lines == null || lines.isEmpty()) {
            if (afterStartupScreen != null) {
                afterStartupScreen.run();
            }
            return;
        }
        startupScreenPrinted.set(false);
        List<String> snapshot = List.copyOf(lines);
        Widget previous = reader.getWidgets().get(LineReader.CALLBACK_INIT);
        reader.getWidgets().put(LineReader.CALLBACK_INIT, () -> {
            boolean ok = previous == null || previous.apply();
            if (startupScreenPrinted.compareAndSet(false, true)) {
                reader.printAbove(joinLines(snapshot));
                if (afterStartupScreen != null) {
                    afterStartupScreen.run();
                }
            }
            return ok;
        });
    }

    /**
     * 清掉 JLine accept 后留在屏幕上的编辑态输入行。
     *
     * <p>普通任务会随后以 {@code > prompt} 的 transcript 块写回；这里清理的是编辑态
     * {@code * prompt}，避免同一条输入在屏幕上出现两次。
     */
    public void clearAcceptedInput(String input) {
        // JLine 已经负责 accept 后的输入行。过去按估算行数上移擦除会在 Windows Terminal
        // resize、全屏切换和 CJK 自动换行后命中错误行；默认 transcript 因此不再改写它。
    }

    public void printSubmittedPrompt(String input) {
        // 已提交输入由 LineReader 留在 scrollback。不要为了深色样式再打印一次，
        // 否则既会重复，也会迫使 clearAcceptedInput 使用不可靠的相对光标定位。
    }

    @Override
    public void appendToolCalls(List<LlmClient.ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return;
        }
        Map<String, List<LlmClient.ToolCall>> grouped = ToolCallRenderer.group(toolCalls);
        FoldableBlock block = new FoldableBlock(out,
                ToolCallRenderer.collapsedHeader(grouped),
                ToolCallRenderer.expandedLines(grouped));
        blockRegistry.register(block);
        TranscriptEntry entry = new BlockEntry(block);
        String rendered = entry.render();
        synchronized (transcriptLock) {
            transcript.add(entry);
            emit(rendered);
        }
    }

    @Override
    public void appendDiff(String filePath, String before, String after) {
        new InlineDiffRenderer(out).render(filePath, before, after);
    }

    @Override
    public void updateStatus(StatusInfo status) {
        if (statusBar != null) {
            statusBar.update(status);
        }
        if (activityDisplay != null) {
            activityDisplay.refreshIfActive();
        }
    }

    @Override
    public ApprovalResult promptApproval(ApprovalRequest request) {
        if (terminal == null) {
            return fallback.promptApproval(request);
        }
        return new InlineApprovalPrompter(out, terminal).prompt(request);
    }

    @Override
    public int openPalette(String title, List<String> items) {
        if (terminal == null) {
            return fallback.openPalette(title, items);
        }
        return new SlashPalette(out, terminal).open(title, items);
    }

    /** 测试可见：当前实例是否启动了 status bar。 */
    public boolean hasStatusBar() {
        return statusBar != null;
    }

    /** 测试 / Main.java 可见：拿到 terminal 用于其它 inline 组件。 */
    public Terminal terminal() {
        return terminal;
    }

    /** Main.java 用：把 Ctrl+O 绑定到 toggleLast。 */
    public BlockRegistry blockRegistry() {
        return blockRegistry;
    }

    /** Main.java 用：Ctrl+O 只把最近工具/代码块详情追加到末尾，绝不重绘历史。 */
    public boolean toggleLastBlock() {
        FoldableBlock block = blockRegistry.expandLastForAppend();
        if (block == null) {
            return false;
        }
        StringBuilder details = new StringBuilder(AnsiStyle.subtle("  ⏷ details\n"));
        for (String line : block.currentLines()) {
            details.append(line).append('\n');
        }
        synchronized (transcriptLock) {
            emit(details.toString());
        }
        return true;
    }

    private PrintStream createTranscriptStream(PrintStream delegate) {
        return new PrintStream(new OutputStream() {
            @Override
            public void write(int b) {
                write(new byte[]{(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) {
                if (len <= 0) {
                    return;
                }
                String text = new String(b, off, len, StandardCharsets.UTF_8);
                synchronized (transcriptLock) {
                    feedWithCodeBlockDetection(text);
                }
            }

            @Override
            public void flush() {
                delegate.flush();
            }
        }, true, StandardCharsets.UTF_8);
    }

    /**
     * 行级状态机：检测 {@code ┌─ code:} / {@code └─ end} 边界，把整段代码块换成
     * {@link FoldableBlock}。代码体在流式期间不写到终端（避免大段文本刷屏）；起始时
     * 追加稳定的“生成中”文本，结束时再追加折叠块。这里不回退或覆盖任何已写出的行，
     * 因此长代码、宽字符换行和异步输出不会破坏 scrollback。
     */
    private void feedWithCodeBlockDetection(String text) {
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            lineBuffer.append(ch);
            if (ch == '\n') {
                String line = lineBuffer.toString();
                lineBuffer.setLength(0);
                processStreamedLine(line);
            }
        }
    }

    private void processStreamedLine(String line) {
        String stripped = stripAnsi(line).trim();

        if (!inCodeBlock && stripped.startsWith("┌─ code")) {
            // 进入代码块：写出稳定提示，body 之后会被缓冲，不使用光标回退替换该提示。
            inCodeBlock = true;
            int colon = stripped.indexOf(':');
            codeLanguage = colon >= 0 ? stripped.substring(colon + 1).trim() : "";
            codeHeaderLine = stripTrailingNewline(line);
            codeBodyLines.clear();
            String label = codeLanguage.isEmpty() ? "code" : "code: " + codeLanguage;
            String pending = AnsiStyle.subtle("  ⏳ generating " + label + "...\n");
            emit(pending);
            transcript.add(new TextEntry(pending));
            return;
        }

        if (inCodeBlock) {
            if (stripped.startsWith("└─ end")) {
                int bodyLineCount = codeBodyLines.size();
                inCodeBlock = false;

                String label = codeLanguage.isEmpty() ? "code" : "code: " + codeLanguage;
                String collapsedHeader = AnsiStyle.subtle(
                        "⏵ " + label + " (" + bodyLineCount + " 行, ctrl+o to expand)");

                List<String> expandedLines = new ArrayList<>();
                expandedLines.add(stripTrailingNewline(codeHeaderLine));
                for (String body : codeBodyLines) {
                    expandedLines.add(stripTrailingNewline(body));
                }
                expandedLines.add(stripTrailingNewline(line));

                FoldableBlock block = new FoldableBlock(out, collapsedHeader, expandedLines, "⏷ collapse (ctrl+o)");
                blockRegistry.register(block);

                transcript.add(new BlockEntry(block));
                emit(collapsedHeader + "\n");

                codeBodyLines.clear();
                codeHeaderLine = null;
                return;
            }
            // body 行：缓冲，不写终端、不入 transcript
            codeBodyLines.add(line);
            return;
        }

        // 非代码块：常规流式
        emit(line);
        transcript.add(new TextEntry(line));
    }

    private LineReader activePrintAboveReader() {
        LineReader reader = lineReader;
        if (reader == null || closed) {
            return null;
        }
        try {
            return reader.isReading() ? reader : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void emit(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        text = stripTerminalEmoji(text);
        if (text.isEmpty()) {
            return;
        }
        LineReader reader = activePrintAboveReader();
        if (reader != null) {
            reader.printAbove(text);
            return;
        }
        out.print(text);
        out.flush();
    }

    /**
     * 部分 Windows Terminal 字体会把彩色 emoji 显示成问号。默认 CLI 只保留
     * ASCII、CJK 和常规排版符号；模型/工具原始数据不受影响，仅清理最终终端副本。
     */
    static String stripTerminalEmoji(String text) {
        StringBuilder clean = new StringBuilder(text.length());
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if ((codePoint >= 0x1F000 && codePoint <= 0x1FAFF)
                    || (codePoint >= 0x2600 && codePoint <= 0x27BF)
                    || codePoint == 0xFE0F || codePoint == 0x200D) {
                continue;
            }
            clean.appendCodePoint(codePoint);
        }
        return clean.toString();
    }

    private static String joinLines(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        String block = String.join("\n", lines);
        return block.endsWith("\n") ? block : block + "\n";
    }

    private static String stripAnsi(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '' && i + 1 < s.length() && s.charAt(i + 1) == '[') {
                int j = i + 2;
                while (j < s.length()) {
                    char c = s.charAt(j);
                    if (c >= '@' && c <= '~') {
                        break;
                    }
                    j++;
                }
                i = j;
                continue;
            }
            sb.append(ch);
        }
        return sb.toString();
    }

    private static String stripTrailingNewline(String s) {
        if (s == null || s.isEmpty()) {
            return s == null ? "" : s;
        }
        int end = s.length();
        if (s.charAt(end - 1) == '\n') {
            end--;
        }
        if (end > 0 && s.charAt(end - 1) == '\r') {
            end--;
        }
        return s.substring(0, end);
    }

    private interface TranscriptEntry {
        String render();
    }

    private record TextEntry(String text) implements TranscriptEntry {
        @Override
        public String render() {
            return text;
        }
    }

    private record BlockEntry(FoldableBlock block) implements TranscriptEntry {
        @Override
        public String render() {
            return String.join(System.lineSeparator(), block.currentLines()) + System.lineSeparator();
        }
    }
}
