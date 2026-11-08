package dev.gdirect.lite.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

/** Auxiliary pages have one place in the task: returning to one removes pages above it. */
public final class AppScreens {
    public static Intent intent(Context context,Class<? extends Activity> screen){
        return new Intent(context,screen).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }
    private AppScreens(){}
}
