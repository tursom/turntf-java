package io.github.tursom.turntf.java;

public final class ServerError extends TurntfException {
    private final String code;
    private final long requestId;

    public ServerError(String code, String message, long requestId) {
        super(requestId == 0
            ? "turntf server error: " + code + " (" + message + ")"
            : "turntf server error: " + code + " (" + message + "), request_id=" + requestId);
        this.code = code;
        this.requestId = requestId;
    }

    public String code() {
        return code;
    }

    public long requestId() {
        return requestId;
    }

    public boolean unauthorized() {
        return "unauthorized".equals(code);
    }
}
