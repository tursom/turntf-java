package io.github.tursom.turntf.java;

public class TurntfException extends RuntimeException {
    public TurntfException(String message) {
        super(message);
    }

    public TurntfException(String message, Throwable cause) {
        super(message, cause);
    }
}
