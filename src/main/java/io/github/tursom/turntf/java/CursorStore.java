package io.github.tursom.turntf.java;

import java.util.List;

/**
 * 游标存储接口，用于持久化和查询消息消费进度。
 * <p>
 * 该接口定义了 SDK 与持久化存储之间的契约，负责管理消息游标（{@link MessageCursor}）的保存和加载。
 * 在 WebSocket 重连时，客户端会将已确认的消息游标列表发送给服务器，服务器据此避免重复投递已消费的消息。
 * 实现类需要保证游标的顺序稳定，并在应用愿意重播消息之前不丢弃条目。
 * <p>
 * <b>使用场景：</b>
 * <ul>
 *   <li>正常消息推送时，先调用 {@link #saveMessage(Message)} 持久化完整消息体，再发送 ACK</li>
 *   <li>重连恢复时，调用 {@link #loadSeenMessages()} 返回已确认的游标列表</li>
 *   <li>当完整消息体不可用时（如已被清理），调用 {@link #saveCursor(MessageCursor)} 仅记录游标</li>
 * </ul>
 */
public interface CursorStore {
    /**
     * 返回客户端已确认（且需要保留）的消息游标列表。
     * <p>
     * 该方法在下一次 WebSocket 登录时被调用，返回的游标列表会发送给服务器用于去重。
     * 服务器根据此列表抑制重连后的消息重新投递。
     * <p>
     * 实现类应注意：
     * <ul>
     *   <li>保持返回顺序的稳定性（插入顺序优先）</li>
     *   <li>避免主动丢弃条目，直到应用明确表示愿意重播相应消息</li>
     * </ul>
     *
     * @return 已确认的消息游标列表，按确认顺序排列；不允许返回 {@code null}
     */
    List<MessageCursor> loadSeenMessages();

    /**
     * 持久化完整的消息体。
     * <p>
     * 该方法在客户端向服务器发送消息确认（ACK）之前被调用，确保即使客户端在 ACK 后崩溃，
     * 服务器也不会认为消息已被消费而客户端却丢失了消息体。
     * <p>
     * <b>注意：</b>实现类应当确保该方法在 ACK 发送前同步或异步完成持久化。
     *
     * @param message 需要持久化的完整消息对象，包含接收者、发送者、消息体等全部信息
     */
    void saveMessage(Message message);

    /**
     * 仅记录消息游标，不保存完整消息体。
     * <p>
     * 当完整消息体不再可用时（例如已被应用消费并丢弃），调用此方法仅记录游标位置。
     * 重连握手只需要游标信息即可完成去重，因此实现类可以在此处记录轻量级的占位条目。
     * <p>
     * <b>注意：</b>正常消息推送流程中，{@link #saveMessage(Message)} 和此方法可能同时被调用，
     * 实现类需要处理重复游标的幂等性。
     *
     * @param cursor 需要记录的消息游标
     */
    void saveCursor(MessageCursor cursor);
}
