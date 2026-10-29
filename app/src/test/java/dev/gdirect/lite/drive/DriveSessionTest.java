package dev.gdirect.lite.drive;

import static org.junit.Assert.*;
import org.junit.Test;

public final class DriveSessionTest {
    @Test public void cachedBroadProviderGrantDoesNotBypassExplicitReadOnlyMode()throws Exception {
        DriveSession session=new DriveSession();session.connect("fixture",java.util.Collections.singleton(DriveApi.MANAGE_SCOPE),false);
        assertTrue(session.connected());assertFalse(session.writable());assertTrue(session.scopes().contains(DriveApi.MANAGE_SCOPE));
        assertThrows(java.io.IOException.class,()->session.lease().requireWrite());
    }
    @Test public void aFreshProcessSessionDoesNotReusePersistedPickerGeneration()throws Exception {
        DriveSession oldProcess=new DriveSession(),newProcess=new DriveSession();
        oldProcess.connect("old-account");newProcess.connect("new-account");
        assertNotEquals(oldProcess.lease().id(),newProcess.lease().id());
    }
    @Test public void actualGrantedScopesAreKeptForRevocationAndInvalidScopesPreserveOldSession()throws Exception {
        DriveSession session=new DriveSession();session.connect("fixture",new java.util.HashSet<>(java.util.Arrays.asList(DriveApi.READONLY_SCOPE,DriveApi.MANAGE_SCOPE,"unrelated")));
        assertEquals(2,session.scopes().size());assertTrue(session.writable());DriveSession.Lease lease=session.lease();
        assertThrows(IllegalArgumentException.class,()->session.connect("other",java.util.Collections.singleton("unrelated")));
        lease.requireWrite();assertEquals("fixture",lease.token());session.disconnect();assertTrue(session.scopes().isEmpty());
    }
    @Test public void resourceKeysNeverCrossAccountGenerationsOrInjectHeaders()throws Exception {
        DriveSession session=new DriveSession();session.connect("fixture-one");DriveSession.Lease first=session.lease();
        first.rememberKey("file","key_123");assertEquals("file/key_123",first.resourceHeader("file"));
        assertNull(first.resourceHeader("other"));assertThrows(java.io.IOException.class,()->first.rememberKey("file","x\r\nAuthorization: value"));
        session.connect("fixture-two");assertNull(session.lease().resourceHeader("file"));assertThrows(DriveSession.AuthorizationRequired.class,()->first.resourceHeader("file"));
    }
    @Test public void readonlyLeaseCannotWriteAndOldWriteGrantDoesNotSurviveReconnect() throws Exception {
        DriveSession session=new DriveSession();session.connect("fixture");
        assertThrows(java.io.IOException.class,()->session.lease().requireWrite());
        session.connect("fixture-write",true);DriveSession.Lease write=session.lease();write.requireWrite();
        session.connect("fixture-read");assertThrows(DriveSession.AuthorizationRequired.class,write::requireWrite);
        assertFalse(session.writable());assertThrows(java.io.IOException.class,()->session.lease().requireWrite());
    }
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
