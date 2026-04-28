package io.github.tursom.turntf.java;

public record SessionRef(long servingNodeId, String sessionId) {
    public boolean isZero() {
        return servingNodeId == 0 && (sessionId == null || sessionId.isEmpty());
    }

    public boolean valid() {
        return servingNodeId != 0 && sessionId != null && !sessionId.isEmpty();
    }
}
