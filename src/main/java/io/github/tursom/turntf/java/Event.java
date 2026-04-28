package io.github.tursom.turntf.java;

public record Event(
    long sequence,
    long eventId,
    String eventType,
    String aggregate,
    long aggregateNodeId,
    long aggregateId,
    String hlc,
    long originNodeId,
    byte[] eventJson
) {
}
