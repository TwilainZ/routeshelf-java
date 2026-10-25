package dev.gdirect.lite;

import static org.junit.Assert.*;
import org.junit.Test;

public final class DriveSessionTest {
    @Test public void disconnectAndAccountChangeInvalidateInflightWork() throws Exception {
        DriveSession session = new DriveSession();
        assertThrows(DriveSession.AuthorizationRequired.class, session::lease);
        session.connect("test-token-one");
        DriveSession.Lease first = session.lease();
        session.connect("test-token-two");
        assertThrows(DriveSession.AuthorizationRequired.class, first::requireCurrent);
        DriveSession.Lease second = session.lease();
        session.invalidate(first);
        second.requireCurrent();
        session.disconnect();
        assertThrows(DriveSession.AuthorizationRequired.class, second::requireCurrent);
    }

    @Test public void malformedTokensCannotInjectHeaders() {
        DriveSession session = new DriveSession();
        for (String bad : new String[]{"", "a b", "a\rb", "a\nb", "a\nb\nc", "a\u0000b", null})
            assertThrows(IllegalArgumentException.class, () -> session.connect(bad));
    }
}
