package h_aaa.mcqqbridge.util;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public final class NamedThreadFactory implements ThreadFactory {
    private final String prefix;
    private final AtomicInteger sequence = new AtomicInteger();

    public NamedThreadFactory(String prefix) {
        this.prefix = prefix;
    }

    @Override
    public Thread newThread(Runnable runnable) {
        Thread thread = new Thread(runnable, prefix + sequence.incrementAndGet());
        thread.setDaemon(true);
        thread.setUncaughtExceptionHandler((ignored, error) -> error.printStackTrace());
        return thread;
    }
}
