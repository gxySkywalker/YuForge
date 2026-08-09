package com.yuforge.render.inline;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 活动 {@link FoldableBlock} 注册表。
 *
 * <p>维护一个双端队列，新注册的块成为"队尾活跃块"，之前的块被 freeze
 * （因为新输出意味着它们已经向上滚走，无法再覆盖重绘）。
 *
 * <p>默认 inline transcript 只允许追加，不能依赖相对光标回退覆盖历史内容。
 * 因此用户展开时只会把最近一个折叠块的细节追加到当前末尾；历史块不会重绘。
 */
public final class BlockRegistry {

    private final Deque<FoldableBlock> blocks = new ArrayDeque<>();

    /** 注册新块；之前的所有块进入 frozen 状态。 */
    public synchronized void register(FoldableBlock block) {
        for (FoldableBlock existing : blocks) {
            existing.freeze();
        }
        blocks.addLast(block);
    }

    /** Toggle 队尾活跃块（即最近一次 register 的块）。返回是否生效。 */
    public synchronized boolean toggleLast() {
        FoldableBlock last = blocks.peekLast();
        if (last == null) {
            return false;
        }
        return last.toggle();
    }

    /** Toggle 队尾块的内存态，由 transcript 重绘负责真正输出。 */
    public synchronized boolean toggleLastForRedraw() {
        FoldableBlock last = blocks.peekLast();
        if (last == null) {
            return false;
        }
        return last.toggleForRedraw();
    }

    /** 默认普通滚屏安全展开：调用方只把详情追加到末尾，不支持回写收起历史。 */
    synchronized FoldableBlock expandLastForAppend() {
        FoldableBlock last = blocks.peekLast();
        if (last == null || last.isFrozen() || last.isExpanded()) {
            return null;
        }
        last.toggleForRedraw();
        last.freeze();
        return last;
    }

    /** 后续普通输出已经出现，所有现存块都不能再做原地覆盖重绘。 */
    public synchronized void freezeAll() {
        for (FoldableBlock block : blocks) {
            block.freeze();
        }
    }

    /** 清空注册表（如 /clear 时）。 */
    public synchronized void clear() {
        blocks.clear();
    }

    /** 当前注册数量（含 frozen）。 */
    public synchronized int size() {
        return blocks.size();
    }

    /** 测试可见：当前队尾块。 */
    synchronized FoldableBlock peekLast() {
        return blocks.peekLast();
    }
}
