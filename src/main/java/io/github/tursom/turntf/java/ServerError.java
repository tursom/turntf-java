package io.github.tursom.turntf.java;

/**
 * 服务器错误异常，表示服务器返回的业务层错误。
 * <p>
 * 与 {@link ProtocolError} 不同，该异常表示服务器正确处理了请求，但返回了业务层面的错误响应。
 * 包含错误码、错误描述和请求 ID，便于问题排查。
 * <p>
 * 提供了便捷方法 {@link #unauthorized()} 用于快速判断是否为未授权错误。
 *
 * @see TurntfException
 * @see ProtocolError
 */
public final class ServerError extends TurntfException {
    private final String code;
    private final long requestId;

    /**
     * 创建服务器错误异常。
     *
     * @param code      服务器返回的错误码，如 "unauthorized"、"not_found" 等
     * @param message   服务器返回的错误描述信息
     * @param requestId 请求的唯一标识符，为 0 时表示请求 ID 不可用；可用于后续与服务端排查问题
     */
    public ServerError(String code, String message, long requestId) {
        super(requestId == 0
            ? "turntf server error: " + code + " (" + message + ")"
            : "turntf server error: " + code + " (" + message + "), request_id=" + requestId);
        this.code = code;
        this.requestId = requestId;
    }

    /**
     * 返回服务器返回的错误码。
     *
     * @return 错误码字符串，如 "unauthorized"、"not_found"、"internal_error" 等
     */
    public String code() {
        return code;
    }

    /**
     * 返回导致此错误的请求的唯一标识符。
     *
     * @return 请求 ID，如果为 0 则表示该信息不可用
     */
    public long requestId() {
        return requestId;
    }

    /**
     * 快速判断当前错误是否为未授权错误。
     *
     * @return 如果错误码为 "unauthorized" 则返回 {@code true}
     */
    public boolean unauthorized() {
        return "unauthorized".equals(code);
    }
}
