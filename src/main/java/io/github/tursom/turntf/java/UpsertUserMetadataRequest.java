package io.github.tursom.turntf.java;

import java.util.Objects;

/**
 * HTTP JSON metadata 写入请求。
 * <p>
 * 服务端要求 {@code value} 与 {@code typed_value} 二选一，因此该模型也保持同样的互斥语义。
 * 原始字节值便于与 WebSocket/protobuf API 复用，而 typed_value 便于在 HTTP 层表达
 * {@code bool|string|number|json|bytes} 五种结构化写法。
 *
 * @param value      原始字节值；与 {@code typedValue} 互斥
 * @param typedValue HTTP typed_value 视图；与 {@code value} 互斥
 * @param expiresAt  可选过期时间；传 {@code null} 表示永不过期
 */
public record UpsertUserMetadataRequest(
    byte[] value,
    UserMetadataTypedValue typedValue,
    String expiresAt
) {
    public UpsertUserMetadataRequest {
        value = value == null ? null : value.clone();
        if ((value == null) == (typedValue == null)) {
            throw new IllegalArgumentException("exactly one of value or typedValue is required");
        }
    }

    /**
     * 创建原始字节写入请求。
     * <p>
     * 为兼容旧接口，{@code value == null} 会被视为写入空字节数组。
     */
    public UpsertUserMetadataRequest(byte[] value, String expiresAt) {
        this(value == null ? new byte[0] : value, null, expiresAt);
    }

    /**
     * 创建 typed_value 写入请求。
     */
    public UpsertUserMetadataRequest(UserMetadataTypedValue typedValue, String expiresAt) {
        this(null, Objects.requireNonNull(typedValue, "typedValue"), expiresAt);
    }

    /**
     * 创建无过期时间的原始字节写入请求。
     */
    public UpsertUserMetadataRequest(byte[] value) {
        this(value, null);
    }

    /**
     * 创建无过期时间的 typed_value 写入请求。
     */
    public UpsertUserMetadataRequest(UserMetadataTypedValue typedValue) {
        this(typedValue, null);
    }

    /**
     * 原始字节写法的静态工厂。
     */
    public static UpsertUserMetadataRequest raw(byte[] value) {
        return new UpsertUserMetadataRequest(value);
    }

    /**
     * 原始字节写法的静态工厂。
     */
    public static UpsertUserMetadataRequest raw(byte[] value, String expiresAt) {
        return new UpsertUserMetadataRequest(value, expiresAt);
    }

    /**
     * typed_value 写法的静态工厂。
     */
    public static UpsertUserMetadataRequest typed(UserMetadataTypedValue typedValue) {
        return new UpsertUserMetadataRequest(typedValue);
    }

    /**
     * typed_value 写法的静态工厂。
     */
    public static UpsertUserMetadataRequest typed(UserMetadataTypedValue typedValue, String expiresAt) {
        return new UpsertUserMetadataRequest(typedValue, expiresAt);
    }

    @Override
    public byte[] value() {
        return value == null ? null : value.clone();
    }

    /**
     * 返回是否使用了 HTTP typed_value 视图。
     */
    public boolean usesTypedValue() {
        return typedValue != null;
    }
}
