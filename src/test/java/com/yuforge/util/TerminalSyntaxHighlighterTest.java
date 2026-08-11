package com.yuforge.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalSyntaxHighlighterTest {

    @Test
    void preservesJavaSourceWhileAddingOnlyAnsiStyles() {
        String source = "public int answer = 42; // stable";

        String highlighted = TerminalSyntaxHighlighter.highlight(source, "java");

        assertEquals(source, stripAnsi(highlighted));
    }

    @Test
    void keepsHashInsideStringAndRecognizesPythonCommentBoundary() {
        String source = "value = \"#not-comment\" # real comment";

        String highlighted = TerminalSyntaxHighlighter.highlight(source, "python");

        assertEquals(source, stripAnsi(highlighted));
        assertTrue(highlighted.contains("# real comment"));
    }

    private static String stripAnsi(String value) {
        return value.replaceAll("\\u001B\\[[;\\d]*m", "");
    }
}
