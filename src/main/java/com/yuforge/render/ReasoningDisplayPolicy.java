package com.yuforge.render;

/**
 * 控制是否把 provider 返回的原始 reasoning 写入用户可见 transcript。
 *
 * <p>默认关闭：终端只保留短暂的 Thinking 活动态和工具进度，避免原始 reasoning
 * 淹没最终回答或被误认为可靠的执行记录。排障时可通过 JVM 属性
 * {@value #SYSTEM_PROPERTY} 或环境变量 {@value #ENVIRONMENT_VARIABLE} 显式开启。
 * 该开关只影响展示，不改变请求历史中为模型协议保留的 reasoning_content，也不影响日志。
 */
public final class ReasoningDisplayPolicy {
    public static final String SYSTEM_PROPERTY = "yuforge.render.show_reasoning";
    public static final String ENVIRONMENT_VARIABLE = "YUFORGE_RENDER_SHOW_REASONING";

    private ReasoningDisplayPolicy() {
    }

    public static boolean showRawReasoning() {
        String configured = System.getProperty(SYSTEM_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(ENVIRONMENT_VARIABLE);
        }
        return Boolean.parseBoolean(configured);
    }
}
