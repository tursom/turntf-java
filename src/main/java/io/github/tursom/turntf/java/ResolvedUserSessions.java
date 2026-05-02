package io.github.tursom.turntf.java;

import java.util.List;

/**
 * 已解析的用户会话信息，包含用户在集群各节点上的在线状态和会话详情。
 * <p>
 * 该类为不可变记录，通常由集群查询接口返回，用于获取某个用户在集群范围内的完整连接状态。
 *
 * @param user     用户引用
 * @param presence 用户在集群各节点上的在线状态列表
 * @param sessions 用户当前的所有活跃会话列表
 */
public record ResolvedUserSessions(
    UserRef user,
    List<OnlineNodePresence> presence,
    List<ResolvedSession> sessions
) {
    /**
     * 节点在线状态，描述用户在某节点上的连接概要信息。
     *
     * @param servingNodeId 服务节点的标识
     * @param sessionCount  该节点上用户当前的会话数量
     * @param transportHint 传输方式提示（如 "websocket"），用于辅助连接决策
     */
    public record OnlineNodePresence(long servingNodeId, int sessionCount, String transportHint) {
    }

    /**
     * 已解析的会话详情，包含会话引用及其传输能力信息。
     *
     * @param session          会话引用，标识集群中的特定会话
     * @param transport        会话所使用的传输协议
     * @param transientCapable 是否支持瞬态消息（不持久化的消息）的传输
     */
    public record ResolvedSession(SessionRef session, String transport, boolean transientCapable) {
    }
}
