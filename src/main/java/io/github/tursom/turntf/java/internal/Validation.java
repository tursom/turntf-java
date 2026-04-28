package io.github.tursom.turntf.java.internal;

import io.github.tursom.turntf.java.DeliveryMode;
import io.github.tursom.turntf.java.ProtocolError;
import io.github.tursom.turntf.java.SessionRef;
import io.github.tursom.turntf.java.UserRef;
import java.net.URI;
import java.net.URISyntaxException;

public final class Validation {
    private Validation() {
    }

    public static void validateBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl is required");
        }
    }

    public static void validateUserRef(UserRef ref, String field) {
        requirePositive(ref.nodeId(), field + ".nodeId");
        requirePositive(ref.userId(), field + ".userId");
    }

    public static void validateSessionRef(SessionRef ref, String field) {
        requirePositive(ref.servingNodeId(), field + ".servingNodeId");
        if (ref.sessionId() == null || ref.sessionId().isEmpty()) {
            throw new IllegalArgumentException(field + ".sessionId is required");
        }
    }

    public static void validateDeliveryMode(DeliveryMode mode) {
        if (mode != DeliveryMode.BEST_EFFORT && mode != DeliveryMode.ROUTE_RETRY) {
            throw new IllegalArgumentException("invalid deliveryMode " + mode);
        }
    }

    public static void requirePositive(long value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    public static long requireUnsigned(long value, String field) {
        if (value < 0) {
            throw new ProtocolError(field + " exceeds signed long range");
        }
        return value;
    }

    public static byte[] copy(byte[] value) {
        return value == null ? new byte[0] : value.clone();
    }

    public static String websocketUrl(String baseUrl, boolean realtime) {
        try {
            URI uri = new URI(baseUrl);
            String scheme = switch (uri.getScheme()) {
                case "http" -> "ws";
                case "https" -> "wss";
                case "ws", "wss" -> uri.getScheme();
                default -> throw new IllegalArgumentException("unsupported base URL scheme \"" + uri.getScheme() + "\"");
            };
            String suffix = realtime ? "/ws/realtime" : "/ws/client";
            String basePath = uri.getPath();
            String path = (basePath == null || basePath.isBlank() || "/".equals(basePath))
                ? suffix
                : basePath.replaceAll("/+$", "") + suffix;
            return new URI(scheme, uri.getUserInfo(), uri.getHost(), uri.getPort(), path, null, null).toString();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
