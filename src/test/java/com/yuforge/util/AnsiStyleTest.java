package com.yuforge.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnsiStyleTest {

    @Test
    void userMessageBlockDoesNotForceWrapWhenContentExactlyFits() {
        String line = AnsiStyle.userMessageBlock("abc", 8);

        assertFalse(line.contains("\n"), line);
        assertTrue(stripAnsi(line).startsWith("> abc"), line);
        assertFalse(stripAnsi(line).startsWith(" "), line);
    }

    @Test
    void userMessageBlockKeepsExplicitMultilineInputAsRows() {
        String block = AnsiStyle.userMessageBlock("第一行\n第二行", 40);

        assertEquals(1, block.chars().filter(ch -> ch == '\n').count(), block);
        assertTrue(block.contains("第一行"), block);
        assertTrue(block.contains("第二行"), block);
    }

    @Test
    void semanticStylesKeepReadableTextAndSeparateMutedMetadata() {
        assertEquals("工具摘要", stripAnsi(AnsiStyle.subtle("工具摘要")));
        assertEquals("耗时", stripAnsi(AnsiStyle.muted("耗时")));
        assertEquals("进行中", stripAnsi(AnsiStyle.status("进行中")));
        assertEquals("完成", stripAnsi(AnsiStyle.success("完成")));
        assertEquals("警告", stripAnsi(AnsiStyle.warning("警告")));

        if (AnsiStyle.isEnabled()) {
            assertTrue(AnsiStyle.subtle("工具摘要").contains("\u001B[90m"));
            assertFalse(AnsiStyle.subtle("工具摘要").contains("\u001B[2m"));
            assertTrue(AnsiStyle.muted("耗时").contains("\u001B[2m"));
        }
    }

    private static String stripAnsi(String value) {
        return value.replaceAll("\u001B\\[[;\\d]*m", "");
    }
}
