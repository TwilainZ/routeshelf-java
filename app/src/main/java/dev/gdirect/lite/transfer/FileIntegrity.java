package dev.gdirect.lite.transfer;

import java.io.IOException;
import java.util.Locale;

public final class FileIntegrity {
    public static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) result.append(String.format(Locale.ROOT, "%02x", b & 255));
        return result.toString();
    }

    public static void verify(long actualSize, long expectedSize, byte[] digest, String expectedMd5) throws IOException {
        if (expectedSize < 0 || actualSize != expectedSize) throw new IOException("文件大小不一致，未保存不完整文件");
        if (expectedMd5 != null && !expectedMd5.isEmpty() && !hex(digest).equalsIgnoreCase(expectedMd5))
            throw new IOException("文件校验失败：远端内容可能已变化，请重新下载");
    }
    private FileIntegrity() { }
}
