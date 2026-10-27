package dev.gdirect.lite;

import java.io.InputStream;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.HashSet;
import java.util.Set;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import okhttp3.OkHttpClient;

/** Isolated Google root store. Public certificates, never a private key or platform CA fallback. */
final class GoogleTrust {
    private static final Set<String> ROOTS = new HashSet<>(java.util.Arrays.asList(
        "d947432abde7b7fa90fc2e6b59101b1280e0e1c7e4e40fa3c6887fff57a7f4cf",
        "8d25cd97229dbf70356bda4eb3cc734031e24cf00fafcfd32dc76eb5841c7ea8",
        "34d8a73ee208d9bcdb0d956520934b4e40e69482596e8b6f73c8426b010a6f48",
        "349dfa4058c5e263123b398ae795573c4e1313c83fe68f93556cd5e8031b3c7d"));
    static X509TrustManager manager() throws Exception {
        KeyStore store=KeyStore.getInstance(KeyStore.getDefaultType());store.load(null);
        Set<String> seen=new HashSet<>();
        try(InputStream in=GoogleTrust.class.getResourceAsStream("/gts-roots.pem")) {
            if(in==null)throw new java.security.cert.CertificateException("Missing Google roots");
            for(java.security.cert.Certificate value:CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                X509Certificate cert=(X509Certificate)value;
                StringBuilder digest=new StringBuilder();
                for(byte b:MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()))digest.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
                if(!ROOTS.contains(digest.toString())||!seen.add(digest.toString())||cert.getBasicConstraints()<0)
                    throw new java.security.cert.CertificateException("Unexpected Google root");
                cert.checkValidity();cert.verify(cert.getPublicKey());store.setCertificateEntry(digest.toString(),cert);
            }
        }
        if(!seen.equals(ROOTS))throw new java.security.cert.CertificateException("Incomplete Google roots");
        TrustManagerFactory factory=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());factory.init(store);
        for(javax.net.ssl.TrustManager manager:factory.getTrustManagers())if(manager instanceof X509TrustManager)return (X509TrustManager)manager;
        throw new java.security.cert.CertificateException("Missing Google trust manager");
    }
    static OkHttpClient.Builder clientBuilder() {
        try {
            X509TrustManager trust=manager();SSLContext tls=SSLContext.getInstance("TLS");tls.init(null,new javax.net.ssl.TrustManager[]{trust},null);
            return new OkHttpClient.Builder().sslSocketFactory(tls.getSocketFactory(),trust);
        }catch(Exception error){throw new IllegalStateException("无法加载 Google 证书，已停止连接",error);}
    }
    private GoogleTrust(){}
}
