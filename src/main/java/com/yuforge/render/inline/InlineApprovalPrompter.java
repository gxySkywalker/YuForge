package com.yuforge.render.inline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuforge.hitl.ApprovalPolicy;
import com.yuforge.hitl.ApprovalRequest;
import com.yuforge.hitl.ApprovalResult;
import com.yuforge.util.AnsiStyle;
import org.jline.terminal.Terminal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Inline 形态的 HITL 审批提示。
 *
 * <p>主菜单使用方向键移动、Enter 确认、Esc 拒绝；拒绝原因和参数修改继续从
 * 同一个 JLine terminal reader 读取，避免输入缓冲器争用。
 *
 * <p>有意保持和 {@link com.yuforge.render.PlainRenderer#promptApproval} 一致的语义；
 * 只是首选项交互更紧凑。
 */
public final class InlineApprovalPrompter {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final PrintStream out;
    private final Terminal terminal;
    private final BufferedReader testLineReader;

    public InlineApprovalPrompter(PrintStream out, Terminal terminal) {
        this(out, terminal, null);
    }

    InlineApprovalPrompter(PrintStream out, Terminal terminal, BufferedReader stdinReader) {
        this.out = out;
        this.terminal = terminal;
        this.testLineReader = stdinReader;
    }

    public ApprovalResult prompt(ApprovalRequest request) {
        boolean sensitive = request.sensitiveNotice() != null && !request.sensitiveNotice().isBlank();
        out.println();
        out.println(AnsiStyle.heading("⚠️  HITL 审批"));
        if (sensitive) {
            out.println("  " + request.sensitiveNotice());
        }
        out.println(request.toDisplayText());

        List<ApprovalChoice> choices = choicesFor(request, sensitive);
        while (true) {
            int selected = new SlashPalette(out, terminal).open(
                    "选择如何处理", choices.stream().map(ApprovalChoice::label).toList(), false);
            if (selected < 0) {
                return ApprovalResult.reject("用户取消审批");
            }
            ApprovalChoice choice = choices.get(selected);
            ApprovalResult result = switch (choice.action()) {
                case APPROVE_ONCE -> ApprovalResult.approve();
                case APPROVE_SESSION -> approveSession(request);
                case REJECT_WITH_REASON -> ApprovalResult.reject(promptForReason());
                case SKIP -> ApprovalResult.skip();
                case MODIFY -> promptForModifiedArgs(request);
            };
            if (result != null) {
                return result;
            }
        }
    }

    private List<ApprovalChoice> choicesFor(ApprovalRequest request, boolean sensitive) {
        List<ApprovalChoice> choices = new ArrayList<>();
        choices.add(new ApprovalChoice("允许本次操作", ApprovalAction.APPROVE_ONCE));
        if (!sensitive) {
            String scope = ApprovalPolicy.approvalScopeKey(request.toolName());
            String label;
            if ("workspace_edit".equals(scope)) {
                label = "允许本会话内的项目文件修改";
            } else if (ApprovalPolicy.isMcpTool(request.toolName())) {
                label = "允许本会话内此 MCP server 的操作";
            } else {
                label = "允许本会话内后续同类操作";
            }
            choices.add(new ApprovalChoice(label, ApprovalAction.APPROVE_SESSION));
        }
        choices.add(new ApprovalChoice("拒绝并告诉 YuForge 原因", ApprovalAction.REJECT_WITH_REASON));
        choices.add(new ApprovalChoice("跳过此操作", ApprovalAction.SKIP));
        choices.add(new ApprovalChoice("修改工具参数后执行", ApprovalAction.MODIFY));
        return choices;
    }

    private ApprovalResult approveSession(ApprovalRequest request) {
        if (ApprovalPolicy.isMcpTool(request.toolName())) {
            out.println(AnsiStyle.subtle("  已允许本会话内此 MCP server 的后续操作"));
            return ApprovalResult.approveAllByServer();
        }
        String scope = ApprovalPolicy.approvalScopeKey(request.toolName());
        String label = "workspace_edit".equals(scope) ? "项目文件修改" : request.toolName();
        out.println(AnsiStyle.subtle("  已允许本会话内后续" + label));
        return ApprovalResult.approveAll();
    }

    private String promptForReason() {
        out.print("  拒绝原因（可直接回车跳过）: ");
        out.flush();
        String line = readTextLine();
        return line == null ? "" : line.trim();
    }

    private ApprovalResult promptForModifiedArgs(ApprovalRequest request) {
        out.println("  当前参数: " + request.arguments());
        out.print("  修改后的 JSON（空行 = 保留原参数）: ");
        out.flush();
        String modified;
        modified = readTextLine();
        if (modified == null || modified.isBlank()) {
            out.println(AnsiStyle.subtle("  保留原参数"));
            return ApprovalResult.approve();
        }
        String trimmed = modified.trim();
        try {
            JSON.readTree(trimmed);
        } catch (Exception e) {
            out.println(AnsiStyle.subtle("  ❌ 非法 JSON: " + e.getMessage()));
            return null;
        }
        return ApprovalResult.modify(trimmed);
    }

    /**
     * 单键选择和后续文本必须从同一个 JLine terminal reader 读取。
     * 混用 terminal.reader() 与 System.in BufferedReader 会让两个缓冲器争抢输入，
     * 在 Windows Terminal 下常表现为拒绝原因被吞掉。
     */
    private String readTextLine() {
        if (testLineReader != null) {
            try {
                return testLineReader.readLine();
            } catch (IOException e) {
                return null;
            }
        }
        StringBuilder line = new StringBuilder();
        try {
            while (true) {
                int ch = terminal.reader().read();
                if (ch < 0) {
                    return line.isEmpty() ? null : line.toString();
                }
                if (ch == '\r' || ch == '\n') {
                    out.println();
                    return line.toString();
                }
                if ((ch == '\b' || ch == 127) && !line.isEmpty()) {
                    line.deleteCharAt(line.length() - 1);
                    out.print("\b \b");
                    out.flush();
                    continue;
                }
                if (!Character.isISOControl(ch)) {
                    line.append((char) ch);
                    out.print((char) ch);
                    out.flush();
                }
            }
        } catch (IOException e) {
            return null;
        }
    }

    private enum ApprovalAction {
        APPROVE_ONCE,
        APPROVE_SESSION,
        REJECT_WITH_REASON,
        SKIP,
        MODIFY
    }

    private record ApprovalChoice(String label, ApprovalAction action) {
    }
}
