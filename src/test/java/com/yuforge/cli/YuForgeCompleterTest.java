package com.yuforge.cli;

import org.jline.reader.Candidate;
import org.jline.reader.ParsedLine;
import org.junit.jupiter.api.Test;
import com.yuforge.mcp.resources.McpResourceDescriptor;
import com.yuforge.skill.Skill;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YuForgeCompleterTest {

    @Test
    void suggestsCompactDiscoveryCommandsWhenInputIsOnlySlash() {
        YuForgeCompleter completer = new YuForgeCompleter(List::of);
        List<Candidate> candidates = new ArrayList<>();

        completer.complete(null, parsed("/", "/"), candidates);

        assertTrue(candidates.stream().anyMatch(c -> c.displ().equals("/model")));
        assertTrue(candidates.stream().anyMatch(c -> c.displ().equals("/init")));
        assertTrue(candidates.stream().anyMatch(c -> c.displ().equals("/plan")));
        assertTrue(candidates.size() <= 8, candidates.toString());
    }

    @Test
    void completesSubCommandWithoutDuplicatingPrefix() {
        YuForgeCompleter completer = new YuForgeCompleter(List::of);
        List<Candidate> candidates = new ArrayList<>();

        completer.complete(null, parsed("/mcp r", "r"), candidates);

        Candidate restart = candidates.stream()
                .filter(c -> c.displ().equals("/mcp restart <name>"))
                .findFirst()
                .orElseThrow();
        assertEquals("restart ", restart.value());
    }

    @Test
    void ignoresNormalWords() {
        YuForgeCompleter completer = new YuForgeCompleter(List::of);
        List<Candidate> candidates = new ArrayList<>();

        completer.complete(null, parsed("hello", "hello"), candidates);

        assertTrue(candidates.isEmpty());
    }

    @Test
    void completesModelProviderNames() {
        YuForgeCompleter completer = new YuForgeCompleter(List::of);
        List<Candidate> candidates = new ArrayList<>();

        completer.complete(null, parsed("/model st", "st"), candidates);

        assertTrue(candidates.stream().anyMatch(c -> c.value().equals("step")));
    }

    @Test
    void completesConfigProviderCommand() {
        YuForgeCompleter completer = new YuForgeCompleter(List::of);
        List<Candidate> candidates = new ArrayList<>();

        completer.complete(null, parsed("/config provider fr", "fr"), candidates);

        assertTrue(candidates.stream().anyMatch(c -> c.value().equals("freellmapi ")));
    }

    @Test
    void completesAgnesProviderCommand() {
        YuForgeCompleter completer = new YuForgeCompleter(List::of);
        List<Candidate> candidates = new ArrayList<>();

        completer.complete(null, parsed("/config provider ag", "ag"), candidates);

        assertTrue(candidates.stream().anyMatch(c -> c.value().equals("agnes ")));
    }

    @Test
    void completesXfyunProviderCommand() {
        YuForgeCompleter completer = new YuForgeCompleter(List::of);
        List<Candidate> candidates = new ArrayList<>();

        completer.complete(null, parsed("/config provider xf", "xf"), candidates);

        assertTrue(candidates.stream().anyMatch(c -> c.value().equals("xfyun ")));
    }

    @Test
    void completesMcpServerNamesFromResources() {
        YuForgeCompleter completer = new YuForgeCompleter(() -> List.of(
                new McpResourceDescriptor("chrome-devtools", "file:///a", "a", "", "", "text/plain", null),
                new McpResourceDescriptor("filesystem", "file:///b", "b", "", "", "text/plain", null)
        ));
        List<Candidate> candidates = new ArrayList<>();

        completer.complete(null, parsed("/mcp logs ch", "ch"), candidates);

        Candidate candidate = candidates.stream()
                .filter(c -> c.value().equals("chrome-devtools"))
                .findFirst()
                .orElseThrow();
        assertEquals("MCP server", candidate.group());
    }

    @Test
    void completesSkillNames() {
        YuForgeCompleter completer = new YuForgeCompleter(List::of, () -> List.of(
                skill("web-access", "浏览器和联网策略"),
                skill("ai-article", "文章写作")
        ));
        List<Candidate> candidates = new ArrayList<>();

        completer.complete(null, parsed("/skill show web", "web"), candidates);

        Candidate candidate = candidates.stream()
                .filter(c -> c.value().equals("web-access"))
                .findFirst()
                .orElseThrow();
        assertEquals("浏览器和联网策略", candidate.descr());
    }

    @Test
    void completesSkillSubCommands() {
        YuForgeCompleter completer = new YuForgeCompleter(List::of);
        List<Candidate> candidates = new ArrayList<>();

        completer.complete(null, parsed("/skill sh", "sh"), candidates);

        assertTrue(candidates.stream().anyMatch(c -> c.value().equals("show ")));
    }

    @Test
    void completesTaskSubCommands() {
        YuForgeCompleter completer = new YuForgeCompleter(List::of);
        List<Candidate> candidates = new ArrayList<>();

        completer.complete(null, parsed("/task ca", "ca"), candidates);

        assertTrue(candidates.stream().anyMatch(c -> c.value().equals("cancel ")));
    }

    @Test
    void completesLocalPathMentions() {
        YuForgeCompleter completer = new YuForgeCompleter(List::of);
        List<Candidate> candidates = new ArrayList<>();

        completer.complete(null, parsed("@pom", "@pom"), candidates);

        assertTrue(candidates.stream().anyMatch(c -> c.value().equals("@pom.xml")));
    }

    @Test
    void completesImagePathMentionsWithTokenPrefix() {
        YuForgeCompleter completer = new YuForgeCompleter(List::of);
        List<Candidate> candidates = new ArrayList<>();

        completer.complete(null, parsed("@image:pom", "@image:pom"), candidates);

        assertTrue(candidates.stream().anyMatch(c -> c.value().equals("@image:pom.xml")));
    }

    private static Skill skill(String name, String description) {
        return new Skill(name, description, "1.0.0", null, List.of(), Skill.Source.USER, "body", null, null);
    }

    private static ParsedLine parsed(String line, String word) {
        return new ParsedLine() {
            @Override public String word() { return word; }
            @Override public int wordCursor() { return word.length(); }
            @Override public int wordIndex() { return 0; }
            @Override public List<String> words() { return List.of(word); }
            @Override public String line() { return line; }
            @Override public int cursor() { return line.length(); }
        };
    }
}
