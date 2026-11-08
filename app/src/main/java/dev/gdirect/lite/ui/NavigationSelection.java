package dev.gdirect.lite.ui;

import java.util.function.IntConsumer;

/** Programmatic Material selection can dispatch both selection and reselection callbacks. */
public final class NavigationSelection {
    private boolean syncing;
    public boolean isSyncing() { return syncing; }

    public void sync(int currentId, int desiredId, IntConsumer select) {
        if (currentId == desiredId) return;
        boolean previous = syncing;
        syncing = true;
        try { select.accept(desiredId); }
        finally { syncing = previous; }
    }
}
