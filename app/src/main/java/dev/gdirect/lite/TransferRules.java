package dev.gdirect.lite;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Protocol invariants shared by probes, ranges, and resumable downloads. */
final class TransferRules {
    private static final Pattern RANGE = Pattern.compile("bytes\\s+(\\d+)-(\\d+)/(\\d+)", Pattern.CASE_INSENSITIVE);

    static String strongEtag(String value) {
        if (value == null || !value.matches("\"[\\x21\\x23-\\x7e]*\"")) return null;
        return value;
    }

    static long rangeTotal(String header, long first, long last) throws IOException {
        Matcher m = RANGE.matcher(header == null ? "" : header.trim());
        try {
            if (m.matches()) {
                long start = Long.parseLong(m.group(1));
                long end = Long.parseLong(m.group(2));
                long total = Long.parseLong(m.group(3));
                if (start == first && end == last && end >= start && total > end) return total;
            }
        } catch (NumberFormatException ignored) { }
        throw new IOException("服务器返回的 Content-Range 无效，已停止拼接文件");
    }

    static void requireRange(String header, long first, long last, long total) throws IOException {
        if (rangeTotal(header, first, last) != total) throw new IOException("远端文件大小已改变，请重新下载");
    }

    static boolean canResume(String oldValidator, String newValidator, long savedSize, long remoteSize) {
        return strongEtag(newValidator) != null && newValidator.equals(oldValidator)
            && remoteSize > 0 && savedSize == remoteSize;
    }

    static int chunkCount(long size, long chunkSize) throws IOException {
        if (size <= 0 || chunkSize <= 0) throw new IOException("文件大小无效");
        long count = 1 + (size - 1) / chunkSize;
        if (count > 100000) throw new IOException("文件过大，暂不支持此文件");
        return (int)count;
    }

    static String safeFilename(String raw) {
        String name = raw == null ? "" : raw.replaceAll("[/\\\\\\p{Cntrl}\\p{Cf}]", "_").trim();
        if (name.length() > 150) name = name.substring(0, 150);
        return name.isEmpty() || name.equals(".") || name.equals("..") ? "gdrive-download.bin" : name;
    }

    private TransferRules() { }
}
