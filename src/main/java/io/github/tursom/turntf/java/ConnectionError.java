package io.github.tursom.turntf.java;

public final class ConnectionError extends TurntfException {
    private final String op;

    public ConnectionError(String op, Throwable cause) {
        super("turntf connection error during " + op + ": " + cause.getMessage(), cause);
        this.op = op;
    }

    public String op() {
        return op;
    }
}
