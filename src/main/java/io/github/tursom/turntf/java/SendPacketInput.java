package io.github.tursom.turntf.java;

public record SendPacketInput(
    UserRef target,
    byte[] body,
    DeliveryMode deliveryMode,
    SessionRef targetSession
) {
}
