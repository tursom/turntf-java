package io.github.tursom.turntf.java;

public record Packet(
    long packetId,
    long sourceNodeId,
    long targetNodeId,
    UserRef recipient,
    UserRef sender,
    byte[] body,
    DeliveryMode deliveryMode,
    SessionRef targetSession
) {
}
