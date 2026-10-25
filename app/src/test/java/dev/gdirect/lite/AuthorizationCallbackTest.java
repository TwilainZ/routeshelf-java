package dev.gdirect.lite;

import static org.junit.Assert.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class AuthorizationCallbackTest {
    @Test public void canceledActivityCanContainConfigurationError() {
        AuthorizationCallback.Result<String> result = AuthorizationCallback.decode(0,"payload", payload -> {
            throw new AuthorizationCallback.StatusFailure(10);
        });
        assertEquals(Integer.valueOf(10),result.status);
        assertNull(result.value);
    }

    @Test public void sdkPayloadIsDecodedInsteadOfDiscardedByActivityCode() {
        AtomicInteger calls = new AtomicInteger();
        AuthorizationCallback.Result<String> result = AuthorizationCallback.decode(0,"payload", payload -> {
            calls.incrementAndGet(); return "decoded-sdk-result";
        });
        assertEquals(1,calls.get());
        assertEquals("decoded-sdk-result",result.value);
        assertNull(result.status);
    }

    @Test public void missingPayloadDoesNotInventUserCancellation() {
        AtomicInteger calls = new AtomicInteger();
        AuthorizationCallback.Result<String> result = AuthorizationCallback.decode(0,null, payload -> {
            calls.incrementAndGet(); return "unexpected";
        });
        assertNull(result.value);
        assertNull(result.status);
        assertEquals(0,result.activityResult);
        assertEquals(0,calls.get());
    }

    @Test public void explicitSdkCancellationIsPreservedAsSdkStatus() {
        AuthorizationCallback.Result<String> result = AuthorizationCallback.decode(-1,"payload", payload -> {
            throw new AuthorizationCallback.StatusFailure(16);
        });
        assertEquals(Integer.valueOf(16),result.status);
        assertNull(result.value);
    }
}
