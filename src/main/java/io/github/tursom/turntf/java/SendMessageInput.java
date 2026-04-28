package io.github.tursom.turntf.java;

public record SendMessageInput(UserRef target, byte[] body) {
}
