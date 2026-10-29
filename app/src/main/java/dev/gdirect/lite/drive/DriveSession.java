package dev.gdirect.lite.drive;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** Access tokens exist only in process memory; a generation invalidates in-flight work. */
public final class DriveSession {
    public static final DriveSession INSTANCE = new DriveSession();
    private String token;
    // Pending file-picker/preview intents from a dead process must not match a new account session.
    private long generation=new java.security.SecureRandom().nextLong()&0x3fffffffffffffffL;
    private long expiresAt;
    private boolean writable;
    private java.util.Set<String> grants=java.util.Collections.emptySet();
    private final java.util.Map<String,String> resourceKeys=new java.util.LinkedHashMap<>(16,0.75f,true);

    public synchronized void connect(String accessToken) {
        connect(accessToken,false);
    }
    public synchronized void connect(String accessToken,boolean writable) {
        connect(accessToken,java.util.Collections.singleton(writable?DriveApi.MANAGE_SCOPE:DriveApi.READONLY_SCOPE));
    }
    public synchronized void connect(String accessToken,java.util.Set<String> scopes) {
        connect(accessToken,scopes,true);
    }
    public synchronized void connect(String accessToken,java.util.Set<String> scopes,boolean allowWrite) {
        if (accessToken == null || !accessToken.matches("[A-Za-z0-9._~+/-]+=*"))
            throw new IllegalArgumentException("Google 未返回有效访问令牌");
        if(scopes==null)throw new IllegalArgumentException("Google 未返回有效权限范围");
        java.util.Set<String> accepted=new java.util.HashSet<>();
        if(scopes.contains(DriveApi.READONLY_SCOPE))accepted.add(DriveApi.READONLY_SCOPE);
        if(scopes.contains(DriveApi.MANAGE_SCOPE))accepted.add(DriveApi.MANAGE_SCOPE);
        if(accepted.isEmpty())throw new IllegalArgumentException("未获得 Google Drive 权限");
        token=accessToken;resourceKeys.clear();
        grants=java.util.Collections.unmodifiableSet(accepted);this.writable=allowWrite&&accepted.contains(DriveApi.MANAGE_SCOPE);
        generation++;
        // Conservative local lifetime; the authorization SDK obtains a fresh token on reconnection.
        expiresAt = System.nanoTime() + TimeUnit.MINUTES.toNanos(50);
    }

    public synchronized Lease lease() throws AuthorizationRequired {
        if (token == null || System.nanoTime() - expiresAt >= 0) {
            disconnect();
            throw new AuthorizationRequired();
        }
        return new Lease(this, generation);
    }

    public synchronized boolean connected() {
        return token != null && System.nanoTime() - expiresAt < 0;
    }

    public synchronized void disconnect() { token = null; writable=false;grants=java.util.Collections.emptySet();resourceKeys.clear();generation++; }
    public synchronized boolean writable() { return connected() && writable; }
    public synchronized java.util.Set<String> scopes(){return connected()?grants:java.util.Collections.emptySet();}

    public synchronized void invalidate(Lease lease) {
        if (lease.owner == this && lease.generation == generation) disconnect();
    }

    public static final class AuthorizationRequired extends IOException {
        public AuthorizationRequired() { super("Google 授权已失效，请重新连接 Google Drive"); }
    }

    public static final class Lease {
        private final DriveSession owner;
        private final long generation;
        private Lease(DriveSession owner, long generation) { this.owner = owner; this.generation = generation; }
        public long id() { return generation; }
        public String token() throws AuthorizationRequired {
            synchronized (owner) {
                if (owner.generation != generation || !owner.connected()) throw new AuthorizationRequired();
                return owner.token;
            }
        }
        public void requireCurrent() throws AuthorizationRequired { token(); }
        public void requireWrite() throws IOException {
            synchronized(owner) { requireCurrent(); if(!owner.writable) throw new IOException("请先启用文件管理授权"); }
        }
        public void rememberKey(String id,String key)throws IOException {
            DriveApi.requireId(id);
            if(key==null||key.isEmpty())return;
            if(!key.matches("[A-Za-z0-9_-]{1,200}"))throw new IOException("资源密钥格式无效");
            synchronized(owner){requireCurrent();if(owner.resourceKeys.size()>=64&&!owner.resourceKeys.containsKey(id))owner.resourceKeys.remove(owner.resourceKeys.keySet().iterator().next());owner.resourceKeys.put(id,key);}
        }
        public String resourceHeader(String id)throws IOException {
            if(id==null)return null;DriveApi.requireId(id);
            synchronized(owner){requireCurrent();String key=owner.resourceKeys.get(id);return key==null?null:id+"/"+key;}
        }
    }
}
