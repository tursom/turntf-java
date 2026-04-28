package io.github.tursom.turntf.java;

public record ClusterNode(
    long nodeId,
    boolean isLocal,
    String configuredUrl,
    String source
) {
}
