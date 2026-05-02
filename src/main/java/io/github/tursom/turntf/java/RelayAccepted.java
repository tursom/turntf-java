package io.github.tursom.turntf.java;

/**
 * 中继（Relay）消息被服务器接受的确认记录。
 * <p>
 * 当客户端通过 {@link TurntfClient} 发送中继消息时，服务器会返回此记录作为接受确认。
 * 中继消息用于在集群内跨节点转发数据包，支持不同的投递模式和目标会话指定。
 *
 * @param packetId      数据包的唯一标识
 * @param sourceNodeId  源节点（发送方所在节点）的标识
 * @param targetNodeId  目标节点（接收方所在节点）的标识
 * @param recipient     中继消息的目标用户引用
 * @param deliveryMode  投递模式，指定消息的投递方式（如持久化或瞬态）
 * @param targetSession 中继消息的目标会话引用，当需要发送到特定会话时有效
 */
public record RelayAccepted(
    long packetId,
    long sourceNodeId,
    long targetNodeId,
    UserRef recipient,
    DeliveryMode deliveryMode,
    SessionRef targetSession
) {
}
