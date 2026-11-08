package dev.gdirect.lite.ui;

import org.junit.Test;
import static org.junit.Assert.*;

public final class TransferTextTest {
    @Test public void completedDownloadShowsItsSizeWithoutAnActiveSpeedOrWaitingLabel(){
        assertEquals("已完成 3.0 MiB",TransferText.amount(3*1024*1024,3*1024*1024,2000,false,true));
        assertEquals("已完成 0 B",TransferText.amount(0,-1,0,false,true));
    }
    @Test public void unknownLengthTransferShowsBytesAndSpeedWithoutInventingPercentage(){
        assertEquals("1.0 KiB · 512 B/s",TransferText.amount(1024,-1,512,true,false));
        assertEquals("1.0 KiB / 2.0 KiB · 512 B/s · 50%",TransferText.amount(1024,2048,512,true,false));
    }
    @Test public void stoppedOrFailedTransferDoesNotReadAsCompleteOrStillRunning(){
        assertEquals("已传输 2.0 KiB",TransferText.amount(2048,4096,256,false,false));
    }
    @Test public void progressIsBoundedAndDoesNotOverflowLargeTransfers(){
        assertEquals(0,TransferText.progress(10,-1));assertEquals(0,TransferText.progress(-1,10));
        assertEquals(1000,TransferText.progress(12,10));assertEquals(1000,TransferText.progress(Long.MAX_VALUE,Long.MAX_VALUE));
        assertEquals(500,TransferText.progress(Long.MAX_VALUE/2,Long.MAX_VALUE));
    }
}
