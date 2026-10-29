package dev.gdirect.lite.auth;

/** Decode Google's result payload even when Android reports RESULT_CANCELED. */
public final class AuthorizationCallback {
    public interface Decoder<P, T> { T decode(P payload) throws StatusFailure; }
    public static final class StatusFailure extends Exception {
        public final int code;
        public StatusFailure(int code) { this.code = code; }
    }
    public static final class Result<T> {
        public final T value;
        public final Integer status;
        public final int activityResult;
        public Result(T value, Integer status, int activityResult) {
            this.value = value; this.status = status; this.activityResult = activityResult;
        }
    }

    public static <P, T> Result<T> decode(int activityResult, P payload, Decoder<P, T> decoder) {
        // A missing payload cannot establish whether the user canceled or Google failed.
        if (payload == null) return new Result<>(null,null,activityResult);
        try { return new Result<>(decoder.decode(payload),null,activityResult); }
        catch (StatusFailure error) { return new Result<>(null,error.code,activityResult); }
    }
    private AuthorizationCallback() { }
}
