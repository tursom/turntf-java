package io.github.tursom.turntf.java;

public record RelayAccepted(
    long packetId,
    long sourceNodeId,
    long targetNodeId,
    UserRef recipient,
    DeliveryMode deliveryMode,
    SessionRef targetSession
) {
}
