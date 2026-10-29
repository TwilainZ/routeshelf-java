package dev.gdirect.lite.transfer;

import static org.junit.Assert.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

public final class WorkerShutdownTest {
    @Test public void interruptionCannotReleasePartialFileWhileWriterIsStillRunning() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        CountDownLatch writing = new CountDownLatch(1), releaseWriter = new CountDownLatch(1);
        CountDownLatch joining = new CountDownLatch(1), returned = new CountDownLatch(1);
        AtomicBoolean interruptionPreserved = new AtomicBoolean();
        pool.submit(() -> {
            writing.countDown();
            // Model a finite blocking file/network operation which does not stop on thread interruption.
            boolean complete = false;
            while (!complete) try { releaseWriter.await(); complete = true; }
            catch (InterruptedException ignored) { }
        });
        Thread owner = new Thread(() -> {
            joining.countDown(); WorkerShutdown.join(pool);
            interruptionPreserved.set(Thread.currentThread().isInterrupted()); returned.countDown();
        });
        try {
            assertTrue(writing.await(1,TimeUnit.SECONDS)); owner.start();
            assertTrue(joining.await(1,TimeUnit.SECONDS)); owner.interrupt();
            assertFalse(returned.await(100,TimeUnit.MILLISECONDS));
            releaseWriter.countDown(); assertTrue(returned.await(1,TimeUnit.SECONDS));
            assertTrue(pool.isTerminated()); assertTrue(interruptionPreserved.get());
        } finally { releaseWriter.countDown(); pool.shutdownNow(); owner.join(1000); }
    }
}
