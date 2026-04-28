package io.github.tursom.turntf.java;

public record Message(
    UserRef recipient,
    long nodeId,
    long seq,
    UserRef sender,
    byte[] body,
    String createdAtHlc
) {
    public MessageCursor cursor() {
        return new MessageCursor(nodeId, seq);
    }
}
