package dev.gdirect.lite.transfer;

import static org.junit.Assert.*;
import java.io.IOException;
import java.security.MessageDigest;
import org.junit.Test;

public final class TransferRulesTest {
    @Test public void rangesRequireExactBoundariesAndTotal() throws Exception {
        assertEquals(10, TransferRules.rangeTotal("bytes 0-0/10", 0, 0));
        TransferRules.requireRange("BYTES 2-5/10", 2, 5, 10);
        for (String value : new String[]{null, "bytes 0-9/*", "bytes 0-10/10", "bytes 1-1/10", "bytes 0-0/0", "bytes 0-0/99999999999999999999"})
            assertThrows(IOException.class, () -> TransferRules.rangeTotal(value, 0, 0));
        assertThrows(IOException.class, () -> TransferRules.requireRange("bytes 0-0/11", 0, 0, 10));
    }

    @Test public void resumeRequiresSameStrongEtagAndSize() {
        assertTrue(TransferRules.canResume("\"v1\"", "\"v1\"", 10, 10));
        assertFalse(TransferRules.canResume("\"v1\"", "\"v2\"", 10, 10));
        assertFalse(TransferRules.canResume(null, null, 10, 10));
        assertFalse(TransferRules.canResume("W/\"v1\"", "W/\"v1\"", 10, 10));
        assertFalse(TransferRules.canResume("\"v1\"", "\"v1\"", 9, 10));
        assertNull(TransferRules.strongEtag("\"x\"\r\nInjected: yes"));
    }

    @Test public void filenamesCannotTraverseOrSpoofDirection() {
        assertEquals(".._report_pdf.exe", TransferRules.safeFilename("../report\u202Epdf.exe"));
        assertEquals("gdrive-download.bin", TransferRules.safeFilename(".."));
        assertEquals("a_b", TransferRules.safeFilename("a\u0000b"));
        assertEquals(150, TransferRules.safeFilename(new String(new char[200]).replace('\0','a')).length());
    }

    @Test public void checksumAndLengthAreVerifiedBeforePublishing() throws Exception {
        byte[] digest = MessageDigest.getInstance("MD5").digest(new byte[]{1,2,3});
        FileIntegrity.verify(3, 3, digest, "5289df737df57326fcdd22597afb1fac");
        assertThrows(IOException.class, () -> FileIntegrity.verify(2, 3, digest, ""));
        assertThrows(IOException.class, () -> FileIntegrity.verify(3, 3, digest, "00000000000000000000000000000000"));
    }

    @Test public void hugeSizesCannotOverflowTheChunkCount() throws Exception {
        assertEquals(2, TransferRules.chunkCount(4194305, 4194304));
        assertThrows(IOException.class, () -> TransferRules.chunkCount(Long.MAX_VALUE,4194304));
        assertThrows(IOException.class, () -> TransferRules.chunkCount(0,4194304));
    }
}
