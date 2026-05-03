package io.github.tursom.turntf.java;

import java.util.Objects;

/**
 * HTTP JSON metadata typed_value 视图。
 * <p>
 * 该模型只用于 HTTP metadata 的结构化读写。WebSocket/protobuf 协议仍然只传输原始字节，
 * 因此通过 {@link TurntfClient} 获取的 {@link UserMetadata#typedValue()} 始终为 {@code null}。
 * <p>
 * 对于 {@link Kind#BYTES}，公开 API 继续使用原始字节而不是 Base64 字符串；编码细节由 HTTP
 * 传输层内部处理。对于 {@link Kind#NUMBER}，SDK 保留 JSON number 的文本表示，避免因为
 * Java 数值类型转换而丢失精度或指数形式。
 *
 * @param kind        typed value 的种类
 * @param bytesValue  当 {@code kind == BYTES} 时的原始字节值
 * @param boolValue   当 {@code kind == BOOL} 时的布尔值
 * @param stringValue 当 {@code kind == STRING} 时的字符串值
 * @param numberValue 当 {@code kind == NUMBER} 时的 JSON number 文本
 * @param jsonValue   当 {@code kind == JSON} 时的原始 JSON 字节
 */
public record UserMetadataTypedValue(
    Kind kind,
    byte[] bytesValue,
    Boolean boolValue,
    String stringValue,
    String numberValue,
    byte[] jsonValue
) {
    public UserMetadataTypedValue {
        kind = Objects.requireNonNull(kind, "kind");
        bytesValue = bytesValue == null ? null : bytesValue.clone();
        jsonValue = jsonValue == null ? null : jsonValue.clone();
        switch (kind) {
            case BYTES -> requireSingleVariant(bytesValue != null, boolValue, stringValue, numberValue, jsonValue, "bytesValue");
            case BOOL -> requireSingleVariant(boolValue != null, bytesValue, stringValue, numberValue, jsonValue, "boolValue");
            case STRING -> requireSingleVariant(stringValue != null, bytesValue, boolValue, numberValue, jsonValue, "stringValue");
            case NUMBER -> requireSingleVariant(numberValue != null, bytesValue, boolValue, stringValue, jsonValue, "numberValue");
            case JSON -> requireSingleVariant(jsonValue != null, bytesValue, boolValue, stringValue, numberValue, "jsonValue");
        }
    }

    /**
     * 创建 bytes 类型的 typed_value。
     */
    public static UserMetadataTypedValue bytes(byte[] value) {
        return new UserMetadataTypedValue(Kind.BYTES, value == null ? new byte[0] : value, null, null, null, null);
    }

    /**
     * 创建 bool 类型的 typed_value。
     */
    public static UserMetadataTypedValue bool(boolean value) {
        return new UserMetadataTypedValue(Kind.BOOL, null, value, null, null, null);
    }

    /**
     * 创建 string 类型的 typed_value。
     */
    public static UserMetadataTypedValue string(String value) {
        return new UserMetadataTypedValue(Kind.STRING, null, null, Objects.requireNonNull(value, "value"), null, null);
    }

    /**
     * 创建 number 类型的 typed_value。
     * <p>
     * {@code value} 必须是合法的 JSON number 文本，例如 {@code 7.5} 或 {@code 1e3}。
     */
    public static UserMetadataTypedValue number(String value) {
        return new UserMetadataTypedValue(Kind.NUMBER, null, null, null, Objects.requireNonNull(value, "value"), null);
    }

    /**
     * 创建 json 类型的 typed_value。
     * <p>
     * {@code value} 必须是合法的单个 JSON 值，对象、数组、字符串、数字、布尔和 {@code null}
     * 都属于合法输入。
     */
    public static UserMetadataTypedValue json(byte[] value) {
        return new UserMetadataTypedValue(Kind.JSON, null, null, null, null, Objects.requireNonNull(value, "value"));
    }

    @Override
    public byte[] bytesValue() {
        return bytesValue == null ? null : bytesValue.clone();
    }

    @Override
    public byte[] jsonValue() {
        return jsonValue == null ? null : jsonValue.clone();
    }

    private static void requireSingleVariant(boolean present, Object a, Object b, Object c, Object d, String name) {
        if (!present) {
            throw new IllegalArgumentException(name + " is required");
        }
        if (a != null || b != null || c != null || d != null) {
            throw new IllegalArgumentException("typed value kind must use exactly one payload field");
        }
    }

    /**
     * HTTP typed_value 支持的种类。
     */
    public enum Kind {
        BYTES("bytes"),
        BOOL("bool"),
        STRING("string"),
        NUMBER("number"),
        JSON("json");

        private final String wireName;

        Kind(String wireName) {
            this.wireName = wireName;
        }

        /**
         * 返回 HTTP JSON 中使用的 kind 字符串。
         */
        public String wireName() {
            return wireName;
        }

        /**
         * 解析 HTTP JSON 中的 kind 字段。
         */
        public static Kind fromWireName(String wireName) {
            for (Kind kind : values()) {
                if (kind.wireName.equals(wireName)) {
                    return kind;
                }
            }
            throw new IllegalArgumentException("unsupported metadata typed value kind " + wireName);
        }
    }
}
