package com.yuforge.runtime;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;

public class CancellationToken {
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final Set<Runnable> callbacks = ConcurrentHashMap.newKeySet();

    public void cancel() {
        if (!cancelled.compareAndSet(false, true)) {
            return;
        }
        for (Runnable callback : callbacks) {
            try {
                callback.run();
            } catch (RuntimeException ignored) {
                // 取消必须尽力通知所有监听方；单个回调失败不能阻断其他 I/O 的取消。
            }
        }
        callbacks.clear();
    }

    public boolean isCancelled() {
        return cancelled.get() || Thread.currentThread().isInterrupted();
    }

    /**
     * 注册取消回调。适用于把协作式 Agent 取消传递给底层阻塞 I/O（例如 OkHttp Call.cancel）。
     */
    public Registration onCancel(Runnable callback) {
        if (callback == null) {
            return Registration.NO_OP;
        }
        if (cancelled.get()) {
            callback.run();
            return Registration.NO_OP;
        }
        callbacks.add(callback);
        // 避免 add 与 cancel 并发发生时遗漏通知。
        if (cancelled.get() && callbacks.remove(callback)) {
            callback.run();
        }
        return () -> callbacks.remove(callback);
    }

    @FunctionalInterface
    public interface Registration extends AutoCloseable {
        Registration NO_OP = () -> { };

        @Override
        void close();
    }
}
