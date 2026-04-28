package io.github.tursom.turntf.java;

public record Subscription(
    UserRef subscriber,
    UserRef channel,
    String subscribedAt,
    String deletedAt,
    long originNodeId
) {
}
