package dev.gdirect.lite.network;

import java.io.EOFException;
import java.io.IOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/** Bounded retries for idempotent reads only. Never retries authorization or TLS validation failures. */
public final class ReadRetry {
    public interface Work<T> { T run() throws IOException; }
    public interface Check { void run() throws IOException; }
    public interface Pause { void sleep(long millis) throws InterruptedException; }
    public static final class Unavailable extends IOException {
        public final long delay;
        public Unavailable(int status, String retryAfter) {
            super("Google 暂时不可用（HTTP " + status + "），请稍后重试");
            delay = delay(retryAfter,System.currentTimeMillis());
        }
    }
    public static long delay(String value,long now) {
        if (value == null) return -1;
        try {
            if (value.trim().matches("[0-9]+")) {
                try { return Math.multiplyExact(Long.parseLong(value.trim()),1000); }
                catch (ArithmeticException | NumberFormatException overflow) { return Long.MAX_VALUE; }
            }
            return Math.max(0,Math.subtractExact(ZonedDateTime.parse(value.trim(),DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli(),now));
        } catch (RuntimeException error) { return -1; }
    }
    public static <T> T run(Work<T> work,Check check) throws IOException { return run(work,check,Thread::sleep); }
    public static <T> T run(Work<T> work,Check check,Pause pause) throws IOException {
        for (int attempt=0;;attempt++) {
            check.run();
            try { return work.run(); }
            catch (IOException error) {
                boolean transientError = error instanceof Unavailable || error instanceof SocketTimeoutException
                    || error instanceof SocketException || error instanceof EOFException || error instanceof UnknownHostException;
                long delay = error instanceof Unavailable ? ((Unavailable)error).delay : -1;
                if (!transientError || attempt >= 2 || delay > 30000) throw error;
                if (delay < 0) delay = 500L << attempt;
                while (delay > 0) {
                    check.run(); long step = Math.min(delay,100);
                    try { pause.sleep(step); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new java.io.InterruptedIOException("操作已取消"); }
                    delay -= step;
                }
            }
        }
    }
    private ReadRetry() { }
}
