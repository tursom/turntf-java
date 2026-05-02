package io.github.tursom.turntf.java;

/**
 * 表示一条 turntf 消息。
 * <p>
 * 该类为不可变记录，包含消息的接收者、发送者、消息体以及时间戳等信息。
 * 消息在集群内通过 {@code nodeId} 和 {@code seq} 唯一标识。
 * 消息体 {@code body} 以原始字节数组形式存储，上层应用可根据需要自行序列化/反序列化。
 *
 * @param recipient    消息的接收用户引用
 * @param nodeId       消息所在节点的标识
 * @param seq          消息在节点内的顺序号，与 {@code nodeId} 共同构成唯一标识
 * @param sender       消息的发送用户引用
 * @param body         消息体的原始字节数据
 * @param createdAtHlc 消息创建时间的混合逻辑时钟（HLC）时间戳
 */
public record Message(
    UserRef recipient,
    long nodeId,
    long seq,
    UserRef sender,
    byte[] body,
    String createdAtHlc
) {
    /**
     * 根据当前消息的 {@code nodeId} 和 {@code seq} 创建一个消息游标。
     * <p>
     * 游标可用于标记消息的消费进度，以及在 {@link CursorStore} 中持久化已处理消息的位置。
     *
     * @return 指向本条消息的游标
     */
    public MessageCursor cursor() {
        return new MessageCursor(nodeId, seq);
    }
}
