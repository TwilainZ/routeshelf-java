package dev.gdirect.lite;

/** Decode Google's result payload even when Android reports RESULT_CANCELED. */
final class AuthorizationCallback {
    interface Decoder<P, T> { T decode(P payload) throws StatusFailure; }
    static final class StatusFailure extends Exception {
        final int code;
        StatusFailure(int code) { this.code = code; }
    }
    static final class Result<T> {
        final T value;
        final Integer status;
        final int activityResult;
        Result(T value, Integer status, int activityResult) {
            this.value = value; this.status = status; this.activityResult = activityResult;
        }
    }

    static <P, T> Result<T> decode(int activityResult, P payload, Decoder<P, T> decoder) {
        // A missing payload cannot establish whether the user canceled or Google failed.
        if (payload == null) return new Result<>(null,null,activityResult);
        try { return new Result<>(decoder.decode(payload),null,activityResult); }
        catch (StatusFailure error) { return new Result<>(null,error.code,activityResult); }
    }
    private AuthorizationCallback() { }
}
