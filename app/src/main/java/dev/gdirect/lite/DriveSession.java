package dev.gdirect.lite;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** Access tokens exist only in process memory; a generation invalidates in-flight work. */
final class DriveSession {
    static final DriveSession INSTANCE = new DriveSession();
    private String token;
    private long generation;
    private long expiresAt;

    synchronized void connect(String accessToken) {
        if (accessToken == null || !accessToken.matches("[A-Za-z0-9._~+/-]+=*"))
            throw new IllegalArgumentException("Google 未返回有效访问令牌");
        token = accessToken;
        generation++;
        // Conservative local lifetime; the authorization SDK obtains a fresh token on reconnection.
        expiresAt = System.nanoTime() + TimeUnit.MINUTES.toNanos(50);
    }

    synchronized Lease lease() throws AuthorizationRequired {
        if (token == null || System.nanoTime() - expiresAt >= 0) {
            disconnect();
            throw new AuthorizationRequired();
        }
        return new Lease(this, generation);
    }

    synchronized boolean connected() {
        return token != null && System.nanoTime() - expiresAt < 0;
    }

    synchronized void disconnect() { token = null; generation++; }

    synchronized void invalidate(Lease lease) {
        if (lease.owner == this && lease.generation == generation) disconnect();
    }

    static final class AuthorizationRequired extends IOException {
        AuthorizationRequired() { super("Google 授权已失效，请重新连接 Google Drive"); }
    }

    static final class Lease {
        private final DriveSession owner;
        private final long generation;
        private Lease(DriveSession owner, long generation) { this.owner = owner; this.generation = generation; }
        long id() { return generation; }
        String token() throws AuthorizationRequired {
            synchronized (owner) {
                if (owner.generation != generation || !owner.connected()) throw new AuthorizationRequired();
                return owner.token;
            }
        }
        void requireCurrent() throws AuthorizationRequired { token(); }
    }
}
