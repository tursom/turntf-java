package io.github.tursom.turntf.java;

public enum DeliveryMode {
    UNSPECIFIED(""),
    BEST_EFFORT("best_effort"),
    ROUTE_RETRY("route_retry");

    private final String wireValue;

    DeliveryMode(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
