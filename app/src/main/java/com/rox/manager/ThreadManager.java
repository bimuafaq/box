package com.rox.manager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class ThreadManager {
    // Single pool for shell commands to avoid resource contention
    private static final ExecutorService shellExecutor = Executors.newSingleThreadExecutor();
    // Pool for light tasks
    private static final ExecutorService workerExecutor = Executors.newFixedThreadPool(4);

    public static void runOnShell(Runnable task) {
        shellExecutor.execute(task);
    }

    public static Future<?> runBackgroundTask(Runnable task) {
        return workerExecutor.submit(task);
    }

    public static void shutdown() {
        shellExecutor.shutdownNow();
        workerExecutor.shutdownNow();
    }
}
