package io.github.tursom.turntf.java;

/**
 * Relay 层的错误，包含错误码和描述信息。
 * <p>
 * 错误码常量用于在 RelayConnection 关闭回调中区分关闭原因。
 */
public class RelayError extends TurntfException {
    private final String code;

    /** OPEN 等待 OPEN_ACK 超时。 */
    public static final String OPEN_TIMEOUT = "open_timeout";
    /** DATA 等待 ACK 超时。 */
    public static final String ACK_TIMEOUT = "ack_timeout";
    /** 超过最大重传次数。 */
    public static final String MAX_RETRANSMIT = "max_retransmit";
    /** 空闲超时断开。 */
    public static final String IDLE_TIMEOUT = "idle_timeout";
    /** 远端关闭连接。 */
    public static final String REMOTE_CLOSE = "remote_close";
    /** 本端已关闭。 */
    public static final String CLIENT_CLOSED = "client_closed";
    /** 协议错误。 */
    public static final String PROTOCOL = "protocol_error";
    /** 并发 OPEN，另一连接被保留。 */
    public static final String DUPLICATE_OPEN = "duplicate_open";
    /** 未连接到目标。 */
    public static final String NOT_CONNECTED = "not_connected";
    /** Send 操作超时。 */
    public static final String SEND_TIMEOUT = "send_timeout";
    /** Receive 操作超时。 */
    public static final String RECEIVE_TIMEOUT = "receive_timeout";

    public RelayError(String code, String message) {
        super("relay: " + code + ": " + message);
        this.code = code;
    }

    public RelayError(String code, String message, Throwable cause) {
        super("relay: " + code + ": " + message, cause);
        this.code = code;
    }

    /**
     * 返回错误码，可用于与常量比较。
     *
     * @return 错误码字符串
     */
    public String code() {
        return code;
    }
}
