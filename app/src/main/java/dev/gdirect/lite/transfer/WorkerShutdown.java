package dev.gdirect.lite.transfer;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** File writers must finish before a cancelled transfer can expose its partial file to another task. */
public final class WorkerShutdown {
    public static void join(ExecutorService pool) {
        pool.shutdownNow();
        boolean interrupted = false;
        try {
            while (true) {
                try { if (pool.awaitTermination(1,TimeUnit.SECONDS)) return; }
                catch (InterruptedException error) { interrupted = true; }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
    private WorkerShutdown() { }
}
