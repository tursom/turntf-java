package io.github.tursom.turntf.java;

import java.time.Duration;
import okhttp3.OkHttpClient;

public record Config(
    String baseUrl,
    Credentials credentials,
    CursorStore cursorStore,
    ClientListener listener,
    OkHttpClient httpClient,
    boolean reconnect,
    Duration initialReconnectDelay,
    Duration maxReconnectDelay,
    Duration pingInterval,
    Duration requestTimeout,
    boolean ackMessages,
    boolean transientOnly,
    boolean realtimeStream
) {
    public Config(String baseUrl, Credentials credentials) {
        this(
            baseUrl,
            credentials,
            null,
            null,
            null,
            true,
            Duration.ofSeconds(1),
            Duration.ofSeconds(30),
            Duration.ofSeconds(30),
            Duration.ofSeconds(10),
            true,
            false,
            false
        );
    }
}
