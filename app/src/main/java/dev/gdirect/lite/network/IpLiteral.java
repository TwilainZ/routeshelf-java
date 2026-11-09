package dev.gdirect.lite.network;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/** Numeric addresses only: no DNS lookup, URI, port or interface/zone identifier. */
public final class IpLiteral {
    private IpLiteral() { }

    public static byte[] bytes(String text) {
        if (text == null || text.isEmpty() || text.length() > 45 || !text.equals(text.trim()))
            throw new IllegalArgumentException("IP 地址无效");
        if (text.indexOf(':') < 0) return ipv4(text);
        if (!text.matches("[0-9a-fA-F:.]+")) throw new IllegalArgumentException("IPv6 地址无效");
        int compression = text.indexOf("::");
        if (compression >= 0 && text.indexOf("::", compression + 2) >= 0)
            throw new IllegalArgumentException("IPv6 地址重复压缩");
        String left = compression < 0 ? text : text.substring(0, compression);
        String right = compression < 0 ? "" : text.substring(compression + 2);
        List<Integer> before = words(left), after = words(right);
        int size = before.size() + after.size();
        if ((compression < 0 && size != 8) || (compression >= 0 && size >= 8)
                || (compression >= 0 && left.indexOf('.') >= 0))
            throw new IllegalArgumentException("IPv6 地址长度无效");
        byte[] result = new byte[16];
        int index = 0;
        for (int word : before) { result[index++] = (byte)(word >>> 8); result[index++] = (byte)word; }
        index = 16 - after.size() * 2;
        for (int word : after) { result[index++] = (byte)(word >>> 8); result[index++] = (byte)word; }
        return result;
    }

    private static byte[] ipv4(String text) {
        String[] parts = text.split("\\.", -1);
        if (parts.length != 4) throw new IllegalArgumentException("IPv4 地址无效");
        byte[] result = new byte[4];
        for (int i = 0; i < 4; i++) {
            String part = parts[i];
            if (!part.matches("0|[1-9][0-9]{0,2}")) throw new IllegalArgumentException("IPv4 地址无效");
            int value = Integer.parseInt(part);
            if (value > 255) throw new IllegalArgumentException("IPv4 地址无效");
            result[i] = (byte)value;
        }
        return result;
    }

    private static List<Integer> words(String text) {
        List<Integer> result = new ArrayList<>();
        if (text.isEmpty()) return result;
        String[] parts = text.split(":", -1);
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.indexOf('.') >= 0 && i == parts.length - 1) {
                byte[] v4 = ipv4(part);
                result.add(((v4[0] & 255) << 8) | (v4[1] & 255));
                result.add(((v4[2] & 255) << 8) | (v4[3] & 255));
            } else {
                if (!part.matches("[0-9a-fA-F]{1,4}")) throw new IllegalArgumentException("IPv6 地址无效");
                result.add(Integer.parseInt(part, 16));
            }
        }
        return result;
    }

    public static String canonical(String text) { return format(bytes(text)); }

    public static String format(byte[] bytes) {
        if (bytes.length == 4) return (bytes[0]&255)+"."+(bytes[1]&255)+"."+(bytes[2]&255)+"."+(bytes[3]&255);
        if (bytes.length != 16) throw new IllegalArgumentException("Invalid address length");
        int[] words = new int[8];
        for (int i = 0; i < 8; i++) words[i] = ((bytes[i*2]&255)<<8) | (bytes[i*2+1]&255);
        int start = -1, length = 1;
        for (int i = 0; i < 8;) {
            if (words[i] != 0) { i++; continue; }
            int end = i; while (end < 8 && words[end] == 0) end++;
            if (end-i > length) { start=i; length=end-i; }
            i=end;
        }
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < 8;) {
            if (i == start) { result.append("::"); i+=length; }
            else {
                if (result.length()>0 && result.charAt(result.length()-1)!=':') result.append(':');
                result.append(Integer.toHexString(words[i++]));
            }
        }
        return result.toString();
    }

    static String format(BigInteger value, int length) {
        byte[] encoded=value.toByteArray(), bytes=new byte[length];
        int count=Math.min(encoded.length,length);
        System.arraycopy(encoded,encoded.length-count,bytes,length-count,count);
        return format(bytes);
    }

    public static boolean ipv6(String value) {
        try { return bytes(value).length == 16; } catch (IllegalArgumentException invalid) { return false; }
    }

    public static boolean publicAddress(String value) {
        try {
            byte[] b=bytes(value);
            if (b.length==16) {
                // Global unicast only. Reject mapped IPv4, local, transition and documentation space.
                if ((b[0]&0xe0)!=0x20) return false;
                BigInteger address=new BigInteger(1,b);
                return !prefix(address,"2001::",23) && !prefix(address,"2001:db8::",32)
                    && !prefix(address,"2002::",16) && !prefix(address,"3fff::",20);
            }
            int a=b[0]&255,c=b[1]&255,d=b[2]&255;
            return a!=0 && a!=10 && a!=127 && a<224 && !(a==100&&c>=64&&c<=127)
                && !(a==169&&c==254) && !(a==172&&c>=16&&c<=31)
                && !(a==192&&(c==168||(c==0&&(d==0||d==2))))
                && !(a==198&&(c==18||c==19||(c==51&&d==100))) && !(a==203&&c==0&&d==113);
        } catch (IllegalArgumentException invalid) { return false; }
    }

    private static boolean prefix(BigInteger value,String base,int bits) {
        return value.shiftRight(128-bits).equals(new BigInteger(1,bytes(base)).shiftRight(128-bits));
    }

    /** Alternate available families without inventing addresses or preferring an unavailable family. */
    public static List<String> interleave(List<String> values) {
        List<String> v4=new ArrayList<>(),v6=new ArrayList<>(),result=new ArrayList<>();
        java.util.LinkedHashSet<String> unique=new java.util.LinkedHashSet<>();
        for(String value:values)if(publicAddress(value))unique.add(canonical(value));
        for(String value:unique)(ipv6(value)?v6:v4).add(value);
        for(int i=0;i<Math.max(v4.size(),v6.size());i++) {
            if(i<v4.size())result.add(v4.get(i));
            if(i<v6.size())result.add(v6.get(i));
        }
        return result;
    }
}
