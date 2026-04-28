package io.github.tursom.turntf.java;

public record LoginInfo(User user, String protocolVersion, SessionRef sessionRef) {
}
