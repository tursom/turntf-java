package io.github.tursom.turntf.java;

public enum AttachmentType {
    CHANNEL_MANAGER("channel_manager"),
    CHANNEL_WRITER("channel_writer"),
    CHANNEL_SUBSCRIPTION("channel_subscription"),
    USER_BLACKLIST("user_blacklist");

    private final String wireValue;

    AttachmentType(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
