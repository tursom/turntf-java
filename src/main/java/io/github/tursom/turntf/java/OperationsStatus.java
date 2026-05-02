package io.github.tursom.turntf.java;

import java.util.List;

/**
 * 节点操作状态记录，提供集群节点的全面运行状况快照。
 * <p>
 * 该记录包含节点的消息处理窗口状态、事件序列、冲突统计、
 * 消息修剪状态、投影状态以及对等节点（Peer）的连接信息等。
 * 主要用于监控和运维场景，帮助理解集群节点的健康状态和数据同步情况。
 *
 * @param nodeId             节点标识
 * @param messageWindowSize  消息窗口大小
 * @param lastEventSequence  最后的事件序列号
 * @param writeGateReady     写入门控是否就绪
 * @param conflictTotal       冲突总数
 * @param messageTrim        消息修剪状态
 * @param projection         投影状态
 * @param peers              对等节点列表
 * @param eventLogTrim       事件日志修剪状态
 */
public record OperationsStatus(
    long nodeId,
    int messageWindowSize,
    long lastEventSequence,
    boolean writeGateReady,
    long conflictTotal,
    MessageTrimStatus messageTrim,
    ProjectionStatus projection,
    List<PeerStatus> peers,
    EventLogTrimStatus eventLogTrim
) {
    /**
     * 消息修剪状态记录。
     *
     * @param trimmedTotal  已修剪的消息总数
     * @param lastTrimmedAt 最后一次修剪操作的时间
     */
    public record MessageTrimStatus(long trimmedTotal, String lastTrimmedAt) {
    }

    /**
     * 事件日志修剪状态记录。
     *
     * @param trimmedTotal  已修剪的事件日志总数
     * @param lastTrimmedAt 最后一次修剪操作的时间
     */
    public record EventLogTrimStatus(long trimmedTotal, String lastTrimmedAt) {
    }

    /**
     * 投影状态记录，描述数据投影的处理进度。
     *
     * @param pendingTotal  待处理的数据投影总数
     * @param lastFailedAt  最后一次投影失败的时间，如果从未失败则为 {@code null}
     */
    public record ProjectionStatus(long pendingTotal, String lastFailedAt) {
    }

    /**
     * 对等节点来源状态记录，描述从特定来源节点的数据同步进度。
     *
     * @param originNodeId     来源节点标识
     * @param ackedEventId     已确认的事件 ID
     * @param appliedEventId   已应用的事件 ID
     * @param unconfirmedEvents 未确认的事件数量
     * @param cursorUpdatedAt  游标最后更新时间
     * @param remoteLastEventId 远程节点的最后事件 ID
     * @param pendingCatchup   是否正在追赶同步中
     */
    public record PeerOriginStatus(
        long originNodeId,
        long ackedEventId,
        long appliedEventId,
        long unconfirmedEvents,
        String cursorUpdatedAt,
        long remoteLastEventId,
        boolean pendingCatchup
    ) {
    }

    /**
     * 对等节点状态记录，描述集群中另一个节点的连接和同步状态。
     * <p>
     * 包含网络连接信息、会话方向、数据同步统计和时钟偏移等详细信息。
     *
     * @param nodeId                     对等节点标识
     * @param configuredUrl              配置的目标 URL
     * @param source                     节点的来源标识
     * @param discoveredUrl              自动发现的实际连接 URL
     * @param discoveryState             节点发现状态（如 "discovered"、"connecting" 等）
     * @param lastDiscoveredAt           最后一次发现该节点的时间
     * @param lastConnectedAt            最后一次成功连接的时间
     * @param lastDiscoveryError         最后一次发现过程中的错误描述
     * @param connected                  当前是否已连接
     * @param sessionDirection           会话方向（如 "outbound" 或 "inbound"）
     * @param origins                    来源节点状态列表
     * @param pendingSnapshotPartitions   待处理的快照分区数
     * @param remoteSnapshotVersion      远程节点的快照版本
     * @param remoteMessageWindowSize    远程节点的消息窗口大小
     * @param clockOffsetMs              与远程节点的时钟偏移量（毫秒）
     * @param lastClockSync              最后一次时钟同步时间
     * @param snapshotDigestsSentTotal    已发送的快照摘要总数
     * @param snapshotDigestsReceivedTotal 已接收的快照摘要总数
     * @param snapshotChunksSentTotal     已发送的快照块总数
     * @param snapshotChunksReceivedTotal 已接收的快照块总数
     * @param lastSnapshotDigestAt       最后一次快照摘要时间
     * @param lastSnapshotChunkAt        最后一次快照块传输时间
     */
    public record PeerStatus(
        long nodeId,
        String configuredUrl,
        String source,
        String discoveredUrl,
        String discoveryState,
        String lastDiscoveredAt,
        String lastConnectedAt,
        String lastDiscoveryError,
        boolean connected,
        String sessionDirection,
        List<PeerOriginStatus> origins,
        int pendingSnapshotPartitions,
        String remoteSnapshotVersion,
        int remoteMessageWindowSize,
        long clockOffsetMs,
        String lastClockSync,
        long snapshotDigestsSentTotal,
        long snapshotDigestsReceivedTotal,
        long snapshotChunksSentTotal,
        long snapshotChunksReceivedTotal,
        String lastSnapshotDigestAt,
        String lastSnapshotChunkAt
    ) {
    }
}
