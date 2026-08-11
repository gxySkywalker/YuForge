package com.yuforge.util;

import java.util.Set;

/**
 * 面向终端代码块的轻量词法高亮器。
 *
 * <p>只识别最稳定的字符串、注释、数字和常见关键字，不尝试替代完整 parser；
 * 即使语言未知也会保留原文，并避免对 ANSI 光标控制做任何操作。</p>
 */
public final class TerminalSyntaxHighlighter {
    private static final Set<String> KEYWORDS = Set.of(
            "abstract", "async", "await", "boolean", "break", "case", "catch", "class", "const",
            "continue", "def", "default", "do", "else", "enum", "export", "extends", "false", "final",
            "finally", "float", "for", "from", "fun", "function", "if", "implements", "import", "in",
            "instanceof", "int", "interface", "let", "long", "new", "null", "package", "private",
            "protected", "public", "record", "return", "static", "super", "switch", "this", "throw",
            "throws", "true", "try", "type", "typeof", "var", "void", "while", "with", "yield");

    private TerminalSyntaxHighlighter() {
    }

    public static String highlight(String source, String language) {
        if (source == null || source.isEmpty() || !AnsiStyle.isEnabled()) {
            return source;
        }
        String lang = language == null ? "" : language.trim().toLowerCase();
        boolean hashComment = lang.equals("python") || lang.equals("py") || lang.equals("bash")
                || lang.equals("sh") || lang.equals("shell") || lang.equals("yaml") || lang.equals("yml");
        StringBuilder result = new StringBuilder(source.length() + 24);
        int i = 0;
        while (i < source.length()) {
            char ch = source.charAt(i);
            if ((hashComment && ch == '#') || (ch == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/')) {
                result.append(AnsiStyle.codeComment(source.substring(i)));
                break;
            }
            if (ch == '"' || ch == '\'') {
                int end = quotedEnd(source, i, ch);
                result.append(AnsiStyle.codeString(source.substring(i, end)));
                i = end;
                continue;
            }
            if (Character.isDigit(ch)) {
                int end = i + 1;
                while (end < source.length() && (Character.isDigit(source.charAt(end))
                        || source.charAt(end) == '.' || source.charAt(end) == '_')) {
                    end++;
                }
                result.append(AnsiStyle.codeNumber(source.substring(i, end)));
                i = end;
                continue;
            }
            if (Character.isJavaIdentifierStart(ch)) {
                int end = i + 1;
                while (end < source.length() && Character.isJavaIdentifierPart(source.charAt(end))) {
                    end++;
                }
                String word = source.substring(i, end);
                result.append(KEYWORDS.contains(word) ? AnsiStyle.codeKeyword(word) : word);
                i = end;
                continue;
            }
            result.append(ch);
            i++;
        }
        return result.toString();
    }

    private static int quotedEnd(String source, int start, char quote) {
        boolean escaped = false;
        for (int i = start + 1; i < source.length(); i++) {
            char ch = source.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (ch == '\\') {
                escaped = true;
            } else if (ch == quote) {
                return i + 1;
            }
        }
        return source.length();
    }
}
