package io.github.tursom.turntf.java;

/**
 * 会话引用，唯一标识集群中某个节点上的一个客户端会话。
 * <p>
 * 每个会话由其所在的服务的节点 ID（{@code servingNodeId}）和会话 ID（{@code sessionId}）共同唯一标识。
 * 该类为不可变记录，用于在集群内定位和引用特定的 WebSocket 会话。
 *
 * @param servingNodeId 会话所在的服务节点标识
 * @param sessionId     会话的唯一标识字符串
 */
public record SessionRef(long servingNodeId, String sessionId) {
    /**
     * 判断此会话引用是否为零值（未初始化）。
     * <p>
     * 当 {@code servingNodeId} 为 0 且 {@code sessionId} 为 {@code null} 或空字符串时返回 {@code true}。
     *
     * @return 如果未设置有效的会话引用则返回 {@code true}
     */
    public boolean isZero() {
        return servingNodeId == 0 && (sessionId == null || sessionId.isEmpty());
    }

    /**
     * 判断此会话引用是否有效。
     * <p>
     * 当 {@code servingNodeId} 不为 0 且 {@code sessionId} 非空时返回 {@code true}。
     *
     * @return 如果会话引用有效则返回 {@code true}
     */
    public boolean valid() {
        return servingNodeId != 0 && sessionId != null && !sessionId.isEmpty();
    }
}
