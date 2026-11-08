package dev.gdirect.lite.ui;

import org.junit.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public final class NavigationSelectionTest {
    @Test public void repeatedListCompletionDoesNotRedispatchTheCurrentTab() {
        NavigationSelection selection=new NavigationSelection();
        AtomicInteger requests=new AtomicInteger();
        for(int i=0;i<100;i++)selection.sync(104,104,id->requests.incrementAndGet());
        assertEquals(0,requests.get());
    }

    @Test public void renderingSelectionAndReselectionCannotLoadButUserEventsCan() {
        NavigationSelection selection=new NavigationSelection();
        AtomicInteger selectedLoads=new AtomicInteger(),reselectedLoads=new AtomicInteger();
        Runnable selected=()->{if(!selection.isSyncing())selectedLoads.incrementAndGet();};
        Runnable reselected=()->{if(!selection.isSyncing())reselectedLoads.incrementAndGet();};
        // Model Material's synchronous callbacks while a view renders its selected item.
        selection.sync(101,104,id->{assertEquals(104,id);selected.run();reselected.run();});
        assertEquals(0,selectedLoads.get());assertEquals(0,reselectedLoads.get());
        selected.run();reselected.run();
        assertEquals(1,selectedLoads.get());assertEquals(1,reselectedLoads.get());
    }

    @Test public void nestedAndFailedRenderingRestoreUserInteraction() {
        NavigationSelection selection=new NavigationSelection();
        assertThrows(IllegalStateException.class,()->selection.sync(101,104,id->{
            selection.sync(104,102,other->assertTrue(selection.isSyncing()));
            assertTrue(selection.isSyncing());throw new IllegalStateException();
        }));
        assertFalse(selection.isSyncing());
    }
}
