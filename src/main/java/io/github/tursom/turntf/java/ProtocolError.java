package io.github.tursom.turntf.java;

/**
 * 协议错误异常，表示 SDK 在 turntf 协议通信过程中遇到的错误。
 * <p>
 * 该异常通常在以下场景抛出：
 * <ul>
 *   <li>HTTP API 返回了意外的状态码</li>
 *   <li>WebSocket 协议帧格式不正确</li>
 *   <li>服务器返回的响应缺少必要字段（如空令牌）</li>
 * </ul>
 *
 * @see TurntfException
 * @see ServerError
 * @see ConnectionError
 */
public final class ProtocolError extends TurntfException {
    /**
     * 创建协议错误异常。
     *
     * @param message 错误描述信息，会被自动添加上 "turntf protocol error: " 前缀
     */
    public ProtocolError(String message) {
        super("turntf protocol error: " + message);
    }
}
