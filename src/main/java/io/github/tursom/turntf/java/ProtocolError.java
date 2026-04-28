package io.github.tursom.turntf.java;

public final class ProtocolError extends TurntfException {
    public ProtocolError(String message) {
        super("turntf protocol error: " + message);
    }
}
