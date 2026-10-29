package dev.gdirect.lite.network;

import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Locale;
import javax.net.ssl.SSLPeerUnverifiedException;
import okhttp3.Interceptor;

/** Additional conservative emSign exclusion, after platform TLS verification and before HTTP data. */
public final class GoogleCertificatePolicy {
    // emSign ECC Root CA - G3 SPKI, identified in webpki-roots removal PR #133.
    private static final String ROOT="8d417db2dd8bf5e3084d1e3f196d583849d81bdd4c00c70b9d39369e96b8c782";
    public static final Interceptor CHECK=chain->{
        okhttp3.Handshake handshake=chain.connection()==null?null:chain.connection().handshake();
        if(handshake==null)throw new SSLPeerUnverifiedException("Google 数据连接缺少 TLS 握手");
        for(Certificate certificate:handshake.peerCertificates()) {
            if(!(certificate instanceof X509Certificate))throw new SSLPeerUnverifiedException("Google 数据连接证书类型不正确");
            X509Certificate cert=(X509Certificate)certificate;
            String names=(cert.getSubjectX500Principal().getName()+","+cert.getIssuerX500Principal().getName()).toLowerCase(Locale.ROOT);
            if(names.contains("emsign")||names.contains("emudhra"))throw denied();
            try {
                byte[] hash=MessageDigest.getInstance("SHA-256").digest(cert.getPublicKey().getEncoded());
                StringBuilder hex=new StringBuilder();for(byte b:hash){hex.append("0123456789abcdef".charAt((b>>>4)&15));hex.append("0123456789abcdef".charAt(b&15));}
                if(ROOT.contentEquals(hex))throw denied();
            }catch(java.security.NoSuchAlgorithmException impossible){throw new SSLPeerUnverifiedException("无法校验证书公钥");}
        }
        return chain.proceed(chain.request());
    };
    private static SSLPeerUnverifiedException denied(){return new SSLPeerUnverifiedException("已拒绝 emSign/eMudhra 证书链，请更换线路并更新系统安全补丁");}
    private GoogleCertificatePolicy(){}
}
