package io.github.tursom.turntf.java;

public record Attachment(
    UserRef owner,
    UserRef subject,
    AttachmentType attachmentType,
    byte[] configJson,
    String attachedAt,
    String deletedAt,
    long originNodeId
) {
}
