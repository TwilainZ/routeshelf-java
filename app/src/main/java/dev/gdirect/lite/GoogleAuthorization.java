package dev.gdirect.lite;

import android.content.Context;
import com.google.android.gms.auth.api.identity.ClearTokenRequest;
import com.google.android.gms.auth.api.identity.Identity;

final class GoogleAuthorization {
    static final java.util.concurrent.atomic.AtomicBoolean revoking = new java.util.concurrent.atomic.AtomicBoolean();
    static volatile boolean lastRevokeSucceeded;
    static DriveTransport transport(Context context, DriveSession.Lease lease) {
        return transport(context,lease,c->{});
    }
    static DriveTransport transport(Context context,DriveSession.Lease lease,java.util.function.Consumer<javax.net.ssl.HttpsURLConnection> observer) {
        Context app = context.getApplicationContext();
        return new DriveTransport(lease, url -> {javax.net.ssl.HttpsURLConnection c=GoogleNetwork.get().open(url,null,lease::requireCurrent);observer.accept(c);return c;}, () -> {
            try {
                String rejected = lease.token();
                DriveSession.INSTANCE.invalidate(lease);
                Identity.getAuthorizationClient(app).clearToken(ClearTokenRequest.builder().setToken(rejected).build());
            } catch (DriveSession.AuthorizationRequired ignored) { }
        });
    }

    private GoogleAuthorization() { }
}
