package io.github.tursom.turntf.java;

/**
 * Relay 连接的生命周期状态。
 * <p>
 * 状态转换：CLOSED → OPENING → OPEN → CLOSING → CLOSED
 */
public enum RelayState {
    /** 初始状态或已关闭。 */
    CLOSED,
    /** 已发送 OPEN，等待 OPEN_ACK。 */
    OPENING,
    /** 连接已建立，可收发数据。 */
    OPEN,
    /** 已发送 CLOSE，等待确认。 */
    CLOSING
}
