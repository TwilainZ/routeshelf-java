package dev.gdirect.lite.network;

import static org.junit.Assert.*;
import java.util.Arrays;
import org.junit.Test;

public final class IpLiteralTest {
    @Test public void numericFormsHaveOneStableCacheIdentity() {
        assertEquals("2001:4860::8888",IpLiteral.canonical("2001:4860:0000:0000:0000:0000:0000:8888"));
        assertEquals("2001:db8::1:0:0:1",IpLiteral.canonical("2001:DB8:0:0:1:0:0:1"));
        assertEquals("::",IpLiteral.canonical("0:0:0:0:0:0:0:0"));
        assertEquals("::1",IpLiteral.canonical("::1"));
        assertEquals("2001:4860::c000:201",IpLiteral.canonical("2001:4860::192.0.2.1"));
        assertEquals("2001:4860::8888",DirectNet.parseIp(" [2001:4860::8888] "));
        assertEquals("8.8.8.8",DirectNet.parseIp("8.8.8.8"));
    }
    @Test public void hostsScopesPortsAndAmbiguousNumericSyntaxAreRejected() {
        for(String value:Arrays.asList("dns.google","2001:::1","2001::1::2","1:2:3:4:5:6:7","1:2:3:4:5:6:7:8:9",
            "::ffff:999.1.1.1","1.2.3.4::","2001:4860::1%wlan0","[2001:4860::1]:443","2001:4860::1/path",
            " 2001:4860::1","01.2.3.4","0x8.8.8.8","https://[2001:4860::1]"))
            assertThrows(value,IllegalArgumentException.class,()->IpLiteral.bytes(value));
    }
    @Test public void discoveryAllowsGlobalFamiliesAndExcludesSpecialUseSpace() {
        assertTrue(IpLiteral.publicAddress("2607:f8b0:4001::200e"));
        assertTrue(IpLiteral.publicAddress("2001:4860::8888"));
        for(String value:Arrays.asList("::","::1","::ffff:8.8.8.8","fc00::1","fe80::1","ff02::1",
            "2001:db8::1","3fff::1","2001:2::1","2002:808:808::1","127.0.0.1","198.18.0.1"))
            assertFalse(value,IpLiteral.publicAddress(value));
    }
    @Test public void aLongAnswerSetCannotHideTheOtherFamily() {
        java.util.List<String> values=new java.util.ArrayList<>();
        for(int i=1;i<=16;i++)values.add("142.250.1."+i);
        values.add("2001:4860::1");values.add("2001:4860:0:0:0:0:0:1");
        java.util.List<String> mixed=IpLiteral.interleave(values);
        assertEquals("2001:4860::1",mixed.get(1));assertEquals(17,mixed.size());
        assertTrue(DirectRoutes.candidates(values,java.util.Collections.emptyList()).contains("2001:4860::1"));
    }
}
