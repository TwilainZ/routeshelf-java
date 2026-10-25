package dev.gdirect.lite;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import com.google.android.gms.common.GoogleApiAvailability;
import java.security.MessageDigest;
import java.util.Locale;

/** Public build/device capability metadata only. Never reads account or token state. */
final class AppDiagnostics {
    static String buildLabel(Context context) {
        String revision = BuildConfig.REVISION;
        return context.getString(R.string.build_identity,BuildConfig.VERSION_NAME,
            revision.substring(0,Math.min(12,revision.length())));
    }

    static String describe(Context context, String phase, Integer activityResult, Integer sdkStatus) {
        StringBuilder out = new StringBuilder();
        out.append("Version: ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")\n");
        out.append("Build: ").append(BuildConfig.REVISION).append("\n");
        out.append("Package: ").append(context.getPackageName()).append("\n");
        out.append("Android: ").append(Build.VERSION.RELEASE).append(" / API ").append(Build.VERSION.SDK_INT).append("\n");
        try {
            PackageInfo app = context.getPackageManager().getPackageInfo(context.getPackageName(),PackageManager.GET_SIGNING_CERTIFICATES);
            Signature[] signers = app.signingInfo == null ? null : app.signingInfo.getApkContentsSigners();
            if (signers == null || signers.length == 0) out.append("SHA-1: unavailable\n");
            else for (Signature signer : signers) {
                byte[] digest = MessageDigest.getInstance("SHA-1").digest(signer.toByteArray());
                StringBuilder hex = new StringBuilder();
                for (byte b : digest) {
                    if (hex.length() > 0) hex.append(':');
                    hex.append(String.format(Locale.ROOT,"%02X",b & 255));
                }
                out.append("SHA-1: ").append(hex).append("\n");
            }
        } catch (PackageManager.NameNotFoundException | java.security.NoSuchAlgorithmException error) {
            out.append("SHA-1: unavailable\n");
        }
        try {
            PackageInfo services = context.getPackageManager().getPackageInfo("com.google.android.gms",0);
            out.append("Google Play services: ").append(services.versionName).append("\n");
        } catch (PackageManager.NameNotFoundException error) { out.append("Google Play services: not installed\n"); }
        int availability = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context);
        out.append("Play services availability: ").append(availability).append("\n");
        out.append("Authorization phase: ").append(phase).append("\n");
        out.append("Activity result: ").append(activityResult == null ? "none" : activityResult).append("\n");
        out.append("Google status: ").append(sdkStatus == null ? "none" : sdkStatus);
        return out.toString();
    }
    private AppDiagnostics() { }
}
