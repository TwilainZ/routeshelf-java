package dev.gdirect.lite.probe;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.res.Resources;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.button.MaterialButton;
import java.security.MessageDigest;
import java.util.Locale;

/** Separate offline package inspector: no authorization and no private app-data access. */
public final class PackageProbeActivity extends AppCompatActivity {
    private static final String TARGET = "dev.gdirect.lite";
    private TextView result;
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (getSupportActionBar() != null) getSupportActionBar().setTitle(R.string.probe_title);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16),dp(16),dp(16),dp(16));
        root.setOnApplyWindowInsetsListener((view,insets) -> {
            android.graphics.Insets bars = android.os.Build.VERSION.SDK_INT >= 30
                ? insets.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout())
                : insets.getSystemWindowInsets();
            view.setPadding(dp(16)+bars.left,dp(16)+bars.top,dp(16)+bars.right,dp(16)+bars.bottom);
            return insets;
        });
        scroll.addView(root); setContentView(scroll); root.requestApplyInsets();
        TextView explanation = new TextView(this); explanation.setText(R.string.probe_explanation); explanation.setTextSize(16);
        explanation.setPadding(0,0,0,dp(16)); root.addView(explanation);
        result = new TextView(this); result.setTextSize(14); result.setTextIsSelectable(true);
        root.addView(result,new LinearLayout.LayoutParams(-1,-2));
        MaterialButton refresh = new MaterialButton(this); refresh.setText(R.string.probe_refresh); refresh.setMinHeight(dp(48));
        refresh.setOnClickListener(view -> refresh()); root.addView(refresh,new LinearLayout.LayoutParams(-1,-2));
        MaterialButton copy = new MaterialButton(this); copy.setText(R.string.diagnostics_copy); copy.setMinHeight(dp(48));
        copy.setOnClickListener(view -> ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE))
            .setPrimaryClip(ClipData.newPlainText(getString(R.string.probe_title),result.getText())));
        root.addView(copy,new LinearLayout.LayoutParams(-1,-2));
        refresh();
    }

    private void refresh() {
        StringBuilder text = new StringBuilder();
        text.append("Inspector: ").append(BuildConfig.VERSION_NAME).append("\n");
        text.append("Inspector build: ").append(BuildConfig.REVISION).append("\n");
        text.append("Target package: ").append(TARGET).append("\n");
        try {
            PackageInfo target = getPackageManager().getPackageInfo(TARGET,PackageManager.GET_SIGNING_CERTIFICATES);
            text.append("Target version: ").append(target.versionName).append(" (").append(target.getLongVersionCode()).append(")\n");
            Signature[] signers = target.signingInfo == null ? null : target.signingInfo.getApkContentsSigners();
            if (signers == null || signers.length == 0) text.append("Target SHA-1: unavailable\n");
            else for (Signature signer : signers) {
                byte[] digest = MessageDigest.getInstance("SHA-1").digest(signer.toByteArray());
                StringBuilder hex = new StringBuilder();
                for (byte value : digest) {
                    if (hex.length() > 0) hex.append(':');
                    hex.append(String.format(Locale.ROOT,"%02X",value & 255));
                }
                text.append("Target SHA-1: ").append(hex).append("\n");
            }
            Resources targetResources = getPackageManager().getResourcesForApplication(TARGET);
            // Only look up public string-resource names. Never read settings, databases, or tokens.
            text.append("Old cancellation resource: ")
                .append(targetResources.getIdentifier("drive_auth_cancelled","string",TARGET) != 0).append("\n");
            text.append("New callback error resource: ")
                .append(targetResources.getIdentifier("drive_auth_no_result","string",TARGET) != 0).append("\n");
            text.append("Installed identity diagnostics: ")
                .append(targetResources.getIdentifier("diagnostics_title","string",TARGET) != 0).append("\n");
        } catch (PackageManager.NameNotFoundException error) {
            text.append("Target not found in this Android user/profile. Open this inspector in the same profile as GDirect Lite.\n");
        } catch (java.security.NoSuchAlgorithmException error) { text.append("Certificate digest unavailable.\n"); }
        text.append("Android API: ").append(android.os.Build.VERSION.SDK_INT).append("\n");
        try {
            PackageInfo services = getPackageManager().getPackageInfo("com.google.android.gms",0);
            text.append("Google Play services: ").append(services.versionName);
        } catch (PackageManager.NameNotFoundException error) { text.append("Google Play services: not installed"); }
        result.setText(text);
    }
}
