package dev.gdirect.lite.drive;

import static org.junit.Assert.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;

public final class MutationCoordinatorTest {
    @Test public void aSecondScreenCannotOverlapAnInFlightWrite()throws Exception {
        try(MutationCoordinator coordinator=new MutationCoordinator()) {
            CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1),done=new CountDownLatch(1);
            try {
                assertTrue(coordinator.submit(()->{started.countDown();try{release.await(2,TimeUnit.SECONDS);}catch(InterruptedException e){throw new java.io.InterruptedIOException();}},error->done.countDown()));
                assertTrue(started.await(1,TimeUnit.SECONDS));assertTrue(coordinator.busy());
                assertFalse(coordinator.submit(()->fail("duplicate write"),error->fail()));
                release.countDown();assertTrue(done.await(1,TimeUnit.SECONDS));assertFalse(coordinator.busy());assertEquals(1,coordinator.revision());
            }finally{release.countDown();}
        }
    }
    @Test public void failedWriteReleasesGateAndPublishesUncertaintyWithoutAutomaticReplay()throws Exception {
        try(MutationCoordinator coordinator=new MutationCoordinator()) {
            CountDownLatch done=new CountDownLatch(1);AtomicInteger calls=new AtomicInteger();
            coordinator.submit(123,()->{calls.incrementAndGet();throw new java.net.SocketException("fixture uncertainty");},error->done.countDown());
            assertTrue(done.await(1,TimeUnit.SECONDS));assertFalse(coordinator.busy());assertEquals(1,calls.get());assertEquals("fixture uncertainty",coordinator.error());assertEquals(123,coordinator.owner());
        }
    }
}
