package io.github.tursom.turntf.java;

import java.util.List;

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
    public record MessageTrimStatus(long trimmedTotal, String lastTrimmedAt) {
    }

    public record EventLogTrimStatus(long trimmedTotal, String lastTrimmedAt) {
    }

    public record ProjectionStatus(long pendingTotal, String lastFailedAt) {
    }

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
