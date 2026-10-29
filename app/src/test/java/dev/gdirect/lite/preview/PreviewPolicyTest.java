package dev.gdirect.lite.preview;

import static org.junit.Assert.*;
import java.io.IOException;
import org.junit.Test;

public final class PreviewPolicyTest {
    @Test public void previewsDoNotExecuteWebContentAndTextHasLowerLimit() {
        assertFalse(PreviewPolicy.supports("text/html"));assertFalse(PreviewPolicy.supports("image/svg+xml"));assertFalse(PreviewPolicy.supports(null));
        assertTrue(PreviewPolicy.supports("application/pdf"));assertTrue(PreviewPolicy.supports("image/png"));assertTrue(PreviewPolicy.supports("text/plain"));
        assertEquals(512*1024,PreviewPolicy.limit("text/plain"));assertEquals(64L*1024*1024,PreviewPolicy.limit("application/pdf"));
    }
    @Test public void oversizedDimensionsAreDownsampledBeforeBitmapAllocation()throws Exception {
        for(int[] size:new int[][]{{100000,100000},{100000,1},{1920,1080},{100,100}}) {
            int sample=PreviewPolicy.sample(size[0],size[1]);assertTrue(sample>=1);
            assertTrue((long)Math.max(1,size[0]/sample)*Math.max(1,size[1]/sample)<=2_000_000);
        }
        assertThrows(IOException.class,()->PreviewPolicy.sample(-1,100));
    }
}
