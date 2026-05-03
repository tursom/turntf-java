package io.github.tursom.turntf.java.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.tursom.turntf.java.DeliveryMode;
import io.github.tursom.turntf.java.UpsertUserMetadataRequest;
import io.github.tursom.turntf.java.ProtocolError;
import io.github.tursom.turntf.java.SessionRef;
import io.github.tursom.turntf.java.UserMetadataTypedValue;
import io.github.tursom.turntf.java.UserRef;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;

public final class Validation {
    private static final int USER_METADATA_KEY_MAX_LENGTH = 128;
    private static final int MAX_USER_METADATA_SCAN_LIMIT = 1000;
    public static final String USER_METADATA_SYSTEM_PREFIX = "system.";
    public static final String USER_METADATA_VISIBLE_TO_OTHERS_KEY = "system.visible_to_others";

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

    public static boolean isZeroUserRef(UserRef ref) {
        return ref == null || (ref.nodeId() == 0 && ref.userId() == 0);
    }

    /**
     * 校验可选的用户过滤器。
     * <p>
     * `list_users` 与 `GET /users` 都把全零 UserRef 视为“未设置 uid 过滤”，但半空坐标会被服务端
     * 视为非法请求。SDK 在本地提前校验，避免把这种模糊状态编码到 HTTP 查询串或 protobuf 消息里。
     */
    public static void validateOptionalUserRef(UserRef ref, String field) {
        if (isZeroUserRef(ref)) {
            return;
        }
        if (ref.nodeId() <= 0 || ref.userId() <= 0) {
            throw new IllegalArgumentException(field + " must set both nodeId and userId, or both be 0");
        }
    }

    public static String encodeUidQuery(UserRef ref, String field) {
        validateOptionalUserRef(ref, field);
        if (isZeroUserRef(ref)) {
            return null;
        }
        return ref.nodeId() + ":" + ref.userId();
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

    public static void validateUserMetadataKey(String key, String field) {
        validateUserMetadataKeyFragment(key, field, false);
        validateKnownSystemUserMetadataKey(key, field);
    }

    public static void validateUserMetadataKeyFragment(String value, String field, boolean allowEmpty) {
        if (value == null || value.isEmpty()) {
            if (allowEmpty) {
                return;
            }
            throw new IllegalArgumentException(field + " is required");
        }
        if (value.length() > USER_METADATA_KEY_MAX_LENGTH) {
            throw new IllegalArgumentException(field + " exceeds " + USER_METADATA_KEY_MAX_LENGTH + " characters");
        }
        for (int idx = 0; idx < value.length(); idx++) {
            char ch = value.charAt(idx);
            boolean allowed = (ch >= 'a' && ch <= 'z')
                || (ch >= 'A' && ch <= 'Z')
                || (ch >= '0' && ch <= '9')
                || ch == '.'
                || ch == '_'
                || ch == ':'
                || ch == '-';
            if (!allowed) {
                throw new IllegalArgumentException(field + " contains unsupported character '" + ch + "'");
            }
        }
    }

    public static void validateUserMetadataScan(String prefix, String after, int limit) {
        validateUserMetadataKeyFragment(prefix, "prefix", true);
        validateUserMetadataKeyFragment(after, "after", true);
        validateKnownSystemUserMetadataPrefix(prefix, "prefix");
        validateKnownSystemUserMetadataPrefix(after, "after");
        if (limit < 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        if (limit > MAX_USER_METADATA_SCAN_LIMIT) {
            throw new IllegalArgumentException("limit cannot exceed " + MAX_USER_METADATA_SCAN_LIMIT);
        }
        if (prefix != null && !prefix.isEmpty() && after != null && !after.isEmpty() && !after.startsWith(prefix)) {
            throw new IllegalArgumentException("after must use the same prefix");
        }
    }

    /**
     * HTTP metadata 写入有一个新的 value/typed_value 二选一边界，且 system key 还叠加了值类型与 TTL
     * 约束。这里集中校验，避免把显然非法的 JSON 形状发给服务端。
     */
    public static void validateUpsertUserMetadataRequest(String key, UpsertUserMetadataRequest request, String field) {
        if (request == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        validateKnownSystemUserMetadataKey(key, "key");
        if (request.typedValue() != null) {
            validateUserMetadataTypedValue(request.typedValue(), field + ".typedValue");
            if (USER_METADATA_VISIBLE_TO_OTHERS_KEY.equals(key) && request.typedValue().kind() != UserMetadataTypedValue.Kind.BOOL) {
                throw new IllegalArgumentException("key " + key + " requires a bool typedValue");
            }
        } else if (USER_METADATA_VISIBLE_TO_OTHERS_KEY.equals(key) && !isBooleanMetadataValue(request.value())) {
            throw new IllegalArgumentException("key " + key + " requires a boolean raw value");
        }
        if (USER_METADATA_VISIBLE_TO_OTHERS_KEY.equals(key) && request.expiresAt() != null) {
            throw new IllegalArgumentException("key " + key + " does not allow expiresAt");
        }
    }

    public static void validateUserMetadataTypedValue(UserMetadataTypedValue typedValue, String field) {
        if (typedValue == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        switch (typedValue.kind()) {
            case BYTES -> {
                if (typedValue.bytesValue() == null) {
                    throw new IllegalArgumentException(field + ".bytesValue is required");
                }
            }
            case BOOL -> {
                if (typedValue.boolValue() == null) {
                    throw new IllegalArgumentException(field + ".boolValue is required");
                }
            }
            case STRING -> {
                if (typedValue.stringValue() == null) {
                    throw new IllegalArgumentException(field + ".stringValue is required");
                }
            }
            case NUMBER -> validateMetadataNumberLiteral(typedValue.numberValue(), field + ".numberValue");
            case JSON -> validateMetadataJsonValue(typedValue.jsonValue(), field + ".jsonValue");
        }
    }

    public static JsonNode parseMetadataNumberLiteral(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        try {
            JsonNode node = JsonCodec.MAPPER.readTree(value);
            if (node == null || !node.isNumber()) {
                throw new IllegalArgumentException(field + " must be a JSON number");
            }
            return node;
        } catch (IOException e) {
            throw new IllegalArgumentException(field + " must be a JSON number", e);
        }
    }

    public static JsonNode parseMetadataJsonValue(byte[] value, String field) {
        if (value == null || value.length == 0) {
            throw new IllegalArgumentException(field + " is required");
        }
        try {
            JsonNode node = JsonCodec.MAPPER.readTree(value);
            if (node == null) {
                throw new IllegalArgumentException(field + " must contain a JSON value");
            }
            return node;
        } catch (IOException e) {
            throw new IllegalArgumentException(field + " must contain valid JSON", e);
        }
    }

    public static boolean isSystemUserMetadataKey(String key) {
        return key != null && key.startsWith(USER_METADATA_SYSTEM_PREFIX);
    }

    public static boolean isBooleanMetadataValue(byte[] value) {
        if (value == null) {
            return false;
        }
        String text = new String(value, StandardCharsets.UTF_8).trim();
        return "true".equals(text) || "false".equals(text);
    }

    private static void validateMetadataNumberLiteral(String value, String field) {
        parseMetadataNumberLiteral(value, field);
    }

    private static void validateMetadataJsonValue(byte[] value, String field) {
        parseMetadataJsonValue(value, field);
    }

    private static void validateKnownSystemUserMetadataKey(String key, String field) {
        if (!isSystemUserMetadataKey(key)) {
            return;
        }
        if (!USER_METADATA_VISIBLE_TO_OTHERS_KEY.equals(key)) {
            throw new IllegalArgumentException(field + " uses unsupported system metadata key " + key);
        }
    }

    // 服务端允许 system prefix 做“前缀中的前缀”扫描，例如 prefix=system. 或 after=system.visible。
    // SDK 本地复刻这条规则，只放行能与当前已注册 system key 命名空间重叠的值。
    private static void validateKnownSystemUserMetadataPrefix(String value, String field) {
        if (!isSystemUserMetadataKey(value)) {
            return;
        }
        if (USER_METADATA_VISIBLE_TO_OTHERS_KEY.startsWith(value) || value.startsWith(USER_METADATA_VISIBLE_TO_OTHERS_KEY)) {
            return;
        }
        throw new IllegalArgumentException(field + " uses unsupported system metadata prefix " + value);
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
