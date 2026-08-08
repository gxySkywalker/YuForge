package com.yuforge.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CancellationContextTest {

    @Test
    void childThreadKeepsRunTokenAfterParentClearsGlobalContext() throws Exception {
        CancellationToken token = CancellationContext.startRun();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch parentCleared = new CountDownLatch(1);
        try {
            Future<Boolean> result = executor.submit(() -> {
                parentCleared.await();
                return CancellationContext.current() == token;
            });

            CancellationContext.clear(token);
            parentCleared.countDown();

            assertTrue(result.get());
        } finally {
            token.cancel();
            CancellationContext.clear(token);
            executor.shutdownNow();
        }
    }

    @Test
    void cancellationInvokesRegisteredCallbacksOnlyOnce() {
        CancellationToken token = new CancellationToken();
        java.util.concurrent.atomic.AtomicInteger callbacks = new java.util.concurrent.atomic.AtomicInteger();
        token.onCancel(callbacks::incrementAndGet);

        token.cancel();
        token.cancel();

        assertTrue(token.isCancelled());
        assertEquals(1, callbacks.get());
    }
}
