package io.github.tursom.turntf.java;

/**
 * 客户端事件监听器，接收来自 {@link TurntfClient} 的实时生命周期和消息投递回调。
 * <p>
 * 回调方法在 SDK 完成对应协议帧所需的处理后触发。具体来说：
 * <ul>
 *   <li>{@link #onLogin(LoginInfo)} 在登录认证完成后调用</li>
 *   <li>{@link #onMessage(Message)} 仅在客户端完成本地持久化并（如果启用）发送 ACK 帧到服务器后调用</li>
 *   <li>{@link #onPacket(Packet)} 在收到数据包后调用</li>
 *   <li>{@link #onError(Throwable)} 和 {@link #onDisconnect(Throwable)} 在出现异常或断开连接时调用</li>
 * </ul>
 * <p>
 * 实现类应当快速处理回调，避免阻塞事件循环。如果需要在回调中执行耗时操作，建议异步处理。
 */
public interface ClientListener {
    /**
     * 用户登录成功后的回调。
     * <p>
     * 当客户端成功完成登录认证并获取到登录信息后触发。
     *
     * @param info 登录信息，包含令牌、用户身份等认证结果数据
     */
    void onLogin(LoginInfo info);

    /**
     * 收到新消息的回调。
     * <p>
     * 在客户端完成本地持久化（如果启用了 {@link CursorStore}）并发送 ACK 帧到服务器后触发。
     * 因此在该回调被调用时，消息已被确认接收。
     *
     * @param message 接收到的消息对象
     */
    void onMessage(Message message);

    /**
     * 收到数据包的回调。
     * <p>
     * 当收到非消息类型的协议数据包时触发（如中继消息、系统通知等）。
     *
     * @param packet 接收到的数据包对象
     */
    void onPacket(Packet packet);

    /**
     * 发生错误的回调。
     * <p>
     * 当客户端在运行过程中遇到非致命错误时触发。此回调不表示连接断开，
     * 仅通知上层发生了需要关注的异常情况。
     *
     * @param error 错误或异常信息
     */
    void onError(Throwable error);

    /**
     * 连接断开的回调。
     * <p>
     * 当客户端与服务器的 WebSocket 连接意外断开时触发。
     * SDK 可能会自动尝试重连，应用层可根据此回调更新连接状态 UI。
     *
     * @param error 导致断开连接的异常信息，如果 {@code null} 则表示正常关闭
     */
    void onDisconnect(Throwable error);
}
