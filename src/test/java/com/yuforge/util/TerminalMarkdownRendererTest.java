package com.yuforge.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalMarkdownRendererTest {
    static {
        System.setProperty("yuforge.render.color", "false");
    }

    @Test
    void rendersHeadingListTableAndCodeBlockToTerminalFriendlyText() {
        String markdown = """
                # 规划思考
                                
                1. **分析请求**
                - 列出当前目录
                                
                | 名称 | 说明 |
                | --- | --- |
                | src | 源码 |
                | pom.xml | Maven 配置 |
                                
                ```java
                System.out.println("hello");
                ```
                """;

        String rendered = TerminalMarkdownRenderer.render(markdown);

        assertTrue(rendered.contains("规划思考"));
        assertTrue(rendered.contains("1. 分析请求"));
        assertTrue(rendered.contains("- 列出当前目录"));
        assertTrue(rendered.contains("| 名称"));
        assertTrue(rendered.contains("| src"));
        assertTrue(rendered.contains("源码"));
        assertTrue(rendered.contains("┌─ code: java"));
        assertTrue(rendered.contains("└─ end"));
        assertTrue(stripAnsi(rendered).contains("    System.out.println(\"hello\");"));
    }

    @Test
    void supportsIncrementalStreamingAppend() {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        java.io.PrintStream stream = new java.io.PrintStream(output);
        TerminalMarkdownRenderer renderer = new TerminalMarkdownRenderer(stream);

        renderer.append("## 标题\n- 第一");
        renderer.append("项\n- 第二项\n");
        renderer.finish();

        String rendered = output.toString();
        assertTrue(rendered.contains("标题"));
        assertTrue(rendered.contains("- 第一项"));
        assertTrue(rendered.contains("- 第二项"));
    }

    @Test
    void preservesNestedListIndentation() {
        String markdown = """
                1. 总体分析
                  - 第一层补充
                    - 第二层补充
                """;

        String rendered = TerminalMarkdownRenderer.render(markdown);

        assertTrue(rendered.contains("1. 总体分析"));
        assertTrue(rendered.contains("  - 第一层补充"));
        assertTrue(rendered.contains("    - 第二层补充"));
    }

    @Test
    void fallsBackToKeyValueLayoutForLongTwoColumnTable() {
        String markdown = """
                | 目录名 | 说明 |
                | --- | --- |
                | src/main/java/com/yuforge | 这里存放 YuForge 的主要 Java 源码实现与相关模块 |
                """;

        String rendered = TerminalMarkdownRenderer.render(markdown);

        assertTrue(rendered.contains("目录名 / 说明"));
        assertTrue(rendered.contains("- src/main/java/com/yuforge"));
        assertTrue(rendered.contains("这里存放 YuForge 的主要 Java 源码实现与相关模块"));
    }

    @Test
    void wrapsWideMultiColumnTableInsideTerminalWidth() {
        String markdown = """
                | 特性 | StepFun (Step) | Kimi | GLM | DeepSeek |
                | --- | --- | --- | --- | --- |
                | 基础 URL | https://api.stepfun.com/v1 | https://api.moonshot.ai/v1 | 动态选择（glm-5v用多模态API，其他用编码API） | https://api.deepseek.com/chat/completions |
                | 推理能力 | ✅（需配置 reasoningformat="deepseek-style"） | ✅（需发送推理历史） | ✅ | ✅ |
                """;

        String rendered = TerminalMarkdownRenderer.render(markdown, 72);

        assertTrue(rendered.contains("- 基础 URL"));
        assertTrue(rendered.contains("StepFun (Step):"));
        assertFalse(rendered.contains("https://api.deepseek.com/chat/completions |"));
        for (String line : rendered.split("\\R")) {
            String visible = stripAnsi(line);
            assertTrue(visible.length() <= 72, "line exceeds table width: " + visible);
        }
    }

    @Test
    void degradesPathHeavyTableToRecordsInsteadOfVerticalCharacters() {
        String markdown = """
                | 路径 | 数量 | 内容 |
                | --- | --- | --- |
                | src/main/java/com/hmdp/controller | 9 | BlogController, FollowController, ShopController |
                | src/main/java/com/hmdp/service/impl | 10 | BlogServiceImpl, ShopServiceImpl, UserServiceImpl |
                """;

        String rendered = TerminalMarkdownRenderer.render(markdown, 48);

        assertTrue(rendered.contains("- src/main/java/com/hmdp/controller"), rendered);
        assertTrue(rendered.contains("数量: 9"), rendered);
        assertTrue(rendered.contains("内容: BlogController"), rendered);
        assertFalse(rendered.contains("| src/"), rendered);
        assertFalse(rendered.contains("| s    |"), rendered);
    }

    @Test
    void wrapsLongParagraphsAndUsesHangingIndentForLists() {
        String markdown = """
                这是一个很长的中文段落，用来确认终端渲染器会主动按照当前列宽换行，而不是依赖 Windows Terminal 自动重排已经写入滚屏的内容。
                - 这是一个很长的列表项目，它换行之后应该保持悬挂缩进，让后续行和正文对齐而不是顶到项目符号下面。
                12. This is a deliberately long ordered item that should wrap with a stable hanging indent.
                """;

        String rendered = TerminalMarkdownRenderer.render(markdown, 40);

        for (String line : rendered.split("\\R")) {
            assertTrue(displayWidth(stripAnsi(line)) <= 40, "line exceeds terminal width: " + line);
        }
        assertTrue(rendered.lines().anyMatch(line -> line.startsWith("  ") && line.contains("保持")), rendered);
        assertTrue(rendered.lines().anyMatch(line -> line.startsWith("    ") && line.contains("stable")), rendered);
    }

    @Test
    void wrapsLongHeadingAndKeyValueRowsWithinTerminalWidth() {
        String markdown = """
                ## 这是一个需要在窄终端中安全换行的超长章节标题而且不能依赖终端自动折行

                | 名称 | 说明 |
                | --- | --- |
                | src/main/java/com/yuforge/very/long/path | 这是很长的说明文本，需要在记录布局中主动换行并保持稳定。 |
                """;

        String rendered = TerminalMarkdownRenderer.render(markdown, 40);

        for (String line : rendered.split("\\R")) {
            assertTrue(displayWidth(stripAnsi(line)) <= 40, "line exceeds terminal width: " + line);
        }
    }

    private static int displayWidth(String value) {
        int width = 0;
        for (int offset = 0; offset < value.length();) {
            int cp = value.codePointAt(offset);
            Character.UnicodeScript script = Character.UnicodeScript.of(cp);
            width += switch (script) {
                case HAN, HIRAGANA, KATAKANA, HANGUL -> 2;
                default -> 1;
            };
            offset += Character.charCount(cp);
        }
        return width;
    }

    private static String stripAnsi(String value) {
        return value.replaceAll("\\u001B\\[[;\\d]*m", "");
    }
}
