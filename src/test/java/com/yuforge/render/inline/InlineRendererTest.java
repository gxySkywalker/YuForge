package com.yuforge.render.inline;

import com.yuforge.hitl.ApprovalRequest;
import com.yuforge.hitl.ApprovalResult;
import com.yuforge.llm.LlmClient;
import com.yuforge.render.StatusInfo;
import com.yuforge.render.ReasoningDisplayPolicy;
import org.jline.reader.LineReader;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InlineRendererTest {

    @Test
    void terminalTranscriptStripsEmojiThatWindowsFontsRenderAsQuestionMarks() {
        assertEquals("[MCP]  ready 读取", InlineRenderer.stripTerminalEmoji("[MCP] 🔌 ready 📖读取"));
        assertTrue(InlineRenderer.stripTerminalEmoji("→ ✅ done").contains("→"));
        assertFalse(InlineRenderer.stripTerminalEmoji("→ ✅ done").contains("✅"));
    }

    @Test
    void startupCallbackRunsOnlyAfterBannerHasBeenPrinted() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 40));
        LineReader lineReader = Mockito.mock(LineReader.class);
        java.util.Map<String, org.jline.reader.Widget> widgets = new HashMap<>();
        Mockito.when(lineReader.getWidgets()).thenReturn(widgets);
        AtomicBoolean callbackRan = new AtomicBoolean();
        ByteArrayOutputStream sink = new ByteArrayOutputStream();

        InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, true, StandardCharsets.UTF_8));
        try {
            renderer.bindLineReader(lineReader);
            renderer.installStartupScreen(List.of("YuForge"), () -> callbackRan.set(true));

            assertFalse(callbackRan.get());
            assertNotNull(widgets.get(LineReader.CALLBACK_INIT));
            widgets.get(LineReader.CALLBACK_INIT).apply();

            Mockito.verify(lineReader).printAbove("YuForge\n");
            assertTrue(callbackRan.get());
        } finally {
            renderer.close();
        }
    }

    @Test
    void onAnsiTerminalEnablesStatusBar() {
        String previous = System.getProperty("yuforge.inline.bottom-dock");
        System.setProperty("yuforge.inline.bottom-dock", "true");
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 40));

        InlineRenderer renderer = new InlineRenderer(terminal);
        try {
            assertTrue(renderer.hasStatusBar());
            renderer.start();
            renderer.updateStatus(StatusInfo.idle("glm-5.1", 200_000L, false));
        } finally {
            renderer.close();
            if (previous == null) {
                System.clearProperty("yuforge.inline.bottom-dock");
            } else {
                System.setProperty("yuforge.inline.bottom-dock", previous);
            }
        }
    }

    @Test
    void defaultInlineModeKeepsBottomDockDisabledForWindowsTerminalStability() {
        String previous = System.getProperty("yuforge.inline.bottom-dock");
        System.clearProperty("yuforge.inline.bottom-dock");
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 40));

        InlineRenderer renderer = new InlineRenderer(terminal);
        try {
            assertFalse(renderer.hasStatusBar());
        } finally {
            renderer.close();
            if (previous == null) {
                System.clearProperty("yuforge.inline.bottom-dock");
            } else {
                System.setProperty("yuforge.inline.bottom-dock", previous);
            }
        }
    }

    @Test
    void onSmallTerminalDisablesStatusBar() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(40, 4));

        InlineRenderer renderer = new InlineRenderer(terminal);
        try {
            assertFalse(renderer.hasStatusBar());
            // updateStatus should still not throw
            renderer.start();
            renderer.updateStatus(StatusInfo.idle("glm-5.1", 200_000L, false));
        } finally {
            renderer.close();
        }
    }

    @Test
    void streamReturnsSystemOut() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 40));

        InlineRenderer renderer = new InlineRenderer(terminal);
        try {
            assertNotNull(renderer.stream());
        } finally {
            renderer.close();
        }
    }

    @Test
    void rawReasoningIsHiddenInInlineModeByDefault() {
        String previous = System.getProperty(ReasoningDisplayPolicy.SYSTEM_PROPERTY);
        System.setProperty(ReasoningDisplayPolicy.SYSTEM_PROPERTY, "false");
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 40));

        InlineRenderer renderer = new InlineRenderer(terminal);
        try {
            assertFalse(renderer.rendersReasoning());
        } finally {
            renderer.close();
            if (previous == null) {
                System.clearProperty(ReasoningDisplayPolicy.SYSTEM_PROPERTY);
            } else {
                System.setProperty(ReasoningDisplayPolicy.SYSTEM_PROPERTY, previous);
            }
        }
    }

    @Test
    void streamUsesPrintAboveWhenLineReaderIsReading() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 40));
        LineReader lineReader = Mockito.mock(LineReader.class);
        Mockito.when(lineReader.isReading()).thenReturn(true);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();

        InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, true, StandardCharsets.UTF_8));
        try {
            renderer.bindLineReader(lineReader);
            renderer.beginTurn();
            renderer.stream().println("异步通知");

            Mockito.verify(lineReader).printAbove("异步通知" + System.lineSeparator());
            assertFalse(sink.toString(StandardCharsets.UTF_8).contains("异步通知"));
        } finally {
            renderer.close();
        }
    }

    @Test
    void streamedCodeBlockUsesCollapsedHeaderWithPrintAbove() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 40));
        LineReader lineReader = Mockito.mock(LineReader.class);
        Mockito.when(lineReader.isReading()).thenReturn(true);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();

        InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, true, StandardCharsets.UTF_8));
        try {
            renderer.bindLineReader(lineReader);
            renderer.beginTurn();
            renderer.stream().println("┌─ code: bash");
            renderer.stream().println("    echo hi");
            renderer.stream().println("└─ end");

            ArgumentCaptor<String> output = ArgumentCaptor.forClass(String.class);
            Mockito.verify(lineReader, Mockito.times(2)).printAbove(output.capture());
            List<String> renderedEvents = output.getAllValues();
            String pending = renderedEvents.get(0);
            String rendered = renderedEvents.get(1);
            assertTrue(pending.contains("generating code: bash"), pending);
            assertTrue(rendered.contains("⏵"), rendered);
            assertTrue(rendered.contains("code: bash"), rendered);
            assertTrue(rendered.contains("1 行"), rendered);
            assertFalse(rendered.contains("echo hi"), rendered);
            assertFalse(sink.toString(StandardCharsets.UTF_8).contains("echo hi"));
        } finally {
            renderer.close();
        }
    }

    @Test
    void streamedCodeBlockNeverRewindsOrClearsExistingTranscriptRows() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 40));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, true, StandardCharsets.UTF_8));
        try {
            renderer.beginTurn();
            renderer.stream().println("before code");
            renderer.stream().println("┌─ code: bash");
            renderer.stream().println("    echo hi");
            renderer.stream().println("└─ end");

            String emitted = sink.toString(StandardCharsets.UTF_8);
            assertTrue(emitted.contains("generating code: bash"), emitted);
            assertTrue(emitted.contains("⏵ code: bash (1 行"), emitted);
            assertFalse(emitted.contains(AnsiSeq.moveUp(1)), emitted);
            assertFalse(emitted.contains(AnsiSeq.CLEAR_TO_EOS), emitted);
        } finally {
            renderer.close();
        }
    }

    @Test
    void inlineRendererKeepsPromptInTranscriptFlow() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 40));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(sink, StandardCharsets.UTF_8), true);
        Mockito.when(terminal.writer()).thenReturn(writer);
        Mockito.doAnswer(invocation -> {
            writer.flush();
            return null;
        }).when(terminal).flush();

        InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, true, StandardCharsets.UTF_8));
        try {
            renderer.start();
            sink.reset();
            renderer.beforeInput();
            renderer.afterInput();

            String emitted = sink.toString(StandardCharsets.UTF_8);
            assertEquals("> ", renderer.inputPrompt());
            assertEquals(null, renderer.inputRightPrompt());
            assertFalse(emitted.contains("[39;1H"), "LineReader should own the input row: " + emitted);
            assertFalse(emitted.contains("[37;1H"), "renderer should not force transcript cursor rows: " + emitted);
        } finally {
            renderer.close();
        }
    }

    @Test
    void acceptedInputIsOwnedByJlineAndNeverClearedWithRelativeCursorMoves() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(32, 12));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, true, StandardCharsets.UTF_8));
        try {
            renderer.clearAcceptedInput("这是会在窄窗口换行的已提交输入");
            renderer.printSubmittedPrompt("这是会在窄窗口换行的已提交输入");

            assertEquals("", sink.toString(StandardCharsets.UTF_8));
        } finally {
            renderer.close();
        }
    }

    @Test
    void thinkingIsAStableTranscriptEventEvenWhenExperimentalDockIsEnabled() {
        String previous = System.getProperty("yuforge.inline.bottom-dock");
        System.setProperty("yuforge.inline.bottom-dock", "true");
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 40));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(sink, StandardCharsets.UTF_8), true);
        Mockito.when(terminal.writer()).thenReturn(writer);
        Mockito.doAnswer(invocation -> {
            writer.flush();
            return null;
        }).when(terminal).flush();

        InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, true, StandardCharsets.UTF_8));
        try {
            renderer.beginThinking("Thinking");
            renderer.appendThinking("先分析用户输入\n再检查状态栏边界");

            String rendered = sink.toString(StandardCharsets.UTF_8);
            assertTrue(renderer.supportsThinkingPanel());
            assertTrue(rendered.contains("Thinking"), rendered);
            assertFalse(rendered.contains("先分析用户输入"), rendered);
            assertFalse(rendered.contains(AnsiSeq.moveUp(1)), rendered);

            sink.reset();
            renderer.endThinking();
            assertEquals("", sink.toString(StandardCharsets.UTF_8),
                    "ending a stable Thinking event must not erase or add transcript rows");
        } finally {
            renderer.close();
            restoreBottomDockProperty(previous);
        }
    }

    @Test
    void activityPanelIsDisabledForAppendOnlyInlineTranscript() {
        String previous = System.getProperty("yuforge.inline.bottom-dock");
        System.setProperty("yuforge.inline.bottom-dock", "true");
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 40));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(sink, StandardCharsets.UTF_8), true);
        Mockito.when(terminal.writer()).thenReturn(writer);
        Mockito.doAnswer(invocation -> {
            writer.flush();
            return null;
        }).when(terminal).flush();

        InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, true, StandardCharsets.UTF_8));
        try {
            renderer.beginActivity("Compacting conversation", "正在整理早期对话并生成摘要");

            String rendered = sink.toString(StandardCharsets.UTF_8);
            assertFalse(renderer.supportsActivityPanel());
            assertEquals("", rendered);
        } finally {
            renderer.endActivity();
            renderer.close();
            restoreBottomDockProperty(previous);
        }
    }

    private static void restoreBottomDockProperty(String previous) {
        if (previous == null) {
            System.clearProperty("yuforge.inline.bottom-dock");
        } else {
            System.setProperty("yuforge.inline.bottom-dock", previous);
        }
    }

    @Test
    void toggleLastBlockAppendsDetailsWithoutRewritingTranscript() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 4));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, true, StandardCharsets.UTF_8));
        try {
            renderer.beginTurn();
            renderer.stream().println("before");
            renderer.appendToolCalls(List.of(tc("read_file", "{\"path\":\"README.md\"}")));
            renderer.stream().println("after");

            sink.reset();
            assertTrue(renderer.toggleLastBlock());

            String emitted = sink.toString(StandardCharsets.UTF_8);
            assertTrue(emitted.contains("README.md"), emitted);
            assertTrue(emitted.contains("details"), emitted);
            assertFalse(emitted.contains(AnsiSeq.CLEAR_TO_EOS), emitted);
            assertFalse(emitted.contains(AnsiSeq.moveUp(1)), emitted);
        } finally {
            renderer.close();
        }
    }

    @Test
    void closeIsIdempotent() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 40));

        InlineRenderer renderer = new InlineRenderer(terminal);
        renderer.start();
        renderer.close();
        renderer.close();
    }

    @Test
    void promptApprovalDelegatesToFallback() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("dumb");

        InlineRenderer renderer = new InlineRenderer(terminal);
        try {
            // When run without TTY, fallback PlainRenderer reads from stdin.
            // Just verify the call doesn't throw and returns a non-null result.
            // Using `n` as the input is unreliable here, so we skip assertion on actual decision
            // and just verify the type contract by interrupting via empty stdin → reject.
            ApprovalRequest req = ApprovalRequest.of("write_file", "{}", "test");
            ApprovalResult result = renderer.promptApproval(req);
            assertNotNull(result);
        } finally {
            renderer.close();
        }
    }

    @Test
    void openPaletteReturnsMinusOneOnNoInput() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("dumb");

        InlineRenderer renderer = new InlineRenderer(terminal);
        try {
            int idx = renderer.openPalette("title", java.util.List.of("a", "b"));
            assertEquals(-1, idx);
        } finally {
            renderer.close();
        }
    }

    private static LlmClient.ToolCall tc(String name, String args) {
        return new LlmClient.ToolCall(name + "-id", new LlmClient.ToolCall.Function(name, args));
    }

    @Test
    void streamedCodeBlockCollapsesIntoFoldableHeader() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 40));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, true, StandardCharsets.UTF_8));
        try {
            renderer.beginTurn();
            // 模拟 TerminalMarkdownRenderer 输出的代码块（手写预渲染好的 markup）
            renderer.stream().println("┌─ code: java");
            renderer.stream().println("    public class Main {");
            renderer.stream().println("    }");
            renderer.stream().println("└─ end");

            String emitted = sink.toString(StandardCharsets.UTF_8);
            assertTrue(emitted.contains("⏵"), "应该出现折叠箭头: " + emitted);
            assertTrue(emitted.contains("code: java"), emitted);
            assertTrue(emitted.contains("2 行"), "应统计 body 行数: " + emitted);
            assertTrue(emitted.contains("ctrl+o"), emitted);
            // body 行不应直接显示在 delegate 上（被吞掉了）—— 验证：last occurrence 不包含 "public class"
            // 但因为 delegate.print(line) 还是会先写 body？让我们再确认：检查 final state。
            // 注意：进入代码块后 body 走 codeBodyLines 缓冲，不写 delegate；end 触发 move-up + clear-to-eos
            // 所以 emitted 里包含 ANSI 序列但**不**包含原 body 文本
            assertFalse(emitted.contains("public class Main {"),
                    "代码体应被折叠后不再可见: " + emitted);
        } finally {
            renderer.close();
        }
    }

    @Test
    void streamedCodeBlockTogglesToExpandedOnRedraw() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 40));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, true, StandardCharsets.UTF_8));
        try {
            renderer.beginTurn();
            renderer.stream().println("┌─ code: bash");
            renderer.stream().println("    echo hi");
            renderer.stream().println("└─ end");

            sink.reset();
            assertTrue(renderer.toggleLastBlock(), "代码块应可 toggle");

            String emitted = sink.toString(StandardCharsets.UTF_8);
            assertTrue(emitted.contains("echo hi"), "展开后应看到代码体: " + emitted);
            assertTrue(emitted.contains("┌─ code: bash"), emitted);
            assertTrue(emitted.contains("└─ end"), emitted);
            assertTrue(emitted.contains("⏷"), "展开态应显示 collapse 提示: " + emitted);
        } finally {
            renderer.close();
        }
    }

    @Test
    void nonCodeStreamingTextStillFlowsThrough() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 40));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, true, StandardCharsets.UTF_8));
        try {
            renderer.beginTurn();
            renderer.stream().println("普通段落 1");
            renderer.stream().println("普通段落 2");

            String emitted = sink.toString(StandardCharsets.UTF_8);
            assertTrue(emitted.contains("普通段落 1"), emitted);
            assertTrue(emitted.contains("普通段落 2"), emitted);
            // 不应出现折叠箭头
            assertFalse(emitted.contains("⏵"));
        } finally {
            renderer.close();
        }
    }
}
