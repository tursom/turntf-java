package io.github.tursom.turntf.java;

import org.mindrot.jbcrypt.BCrypt;

/**
 * 密码输入封装类，支持明文和已哈希两种密码输入源。
 * <p>
 * 该类用于安全地处理用户密码的输入和传输。明文密码会在客户端使用 bcrypt 算法哈希后传输，
 * 避免密码在传输过程中暴露。同时支持直接传入已哈希的密码，适用于需要重复提交的场景。
 * <p>
 * <b>使用示例：</b>
 * <pre>{@code
 * // 从明文密码创建（推荐）
 * PasswordInput input = PasswordInput.plain("myPassword123");
 *
 * // 从已哈希的密码创建
 * PasswordInput input = PasswordInput.hashed("$2a$10$...");
 *
 * // 获取用于传输的值
 * String wireValue = input.wireValue();
 * }</pre>
 */
public final class PasswordInput {
    /**
     * 密码来源类型。
     */
    public enum Source {
        /**
         * 明文密码，需要通过 bcrypt 哈希后传输。
         */
        PLAIN,
        /**
         * 已哈希的密码，可直接用于传输。
         */
        HASHED
    }

    private final Source source;
    private final String encoded;

    private PasswordInput(Source source, String encoded) {
        this.source = source;
        this.encoded = encoded;
    }

    /**
     * 从明文密码创建 {@code PasswordInput} 实例。
     * <p>
     * 该方法会自动对明文密码进行 bcrypt 哈希处理。
     *
     * @param plain 明文密码字符串，不能为 {@code null} 或空
     * @return 包含已哈希密码的 {@code PasswordInput} 实例
     * @throws IllegalArgumentException 如果密码为 {@code null} 或空字符串
     */
    public static PasswordInput plain(String plain) {
        return new PasswordInput(Source.PLAIN, hash(plain));
    }

    /**
     * 从已哈希的密码创建 {@code PasswordInput} 实例。
     * <p>
     * 适用于已有 bcrypt 哈希值的场景，避免重复哈希。
     *
     * @param encoded 已哈希的密码字符串，不能为 {@code null} 或空
     * @return 包含已哈希密码的 {@code PasswordInput} 实例
     */
    public static PasswordInput hashed(String encoded) {
        return new PasswordInput(Source.HASHED, encoded);
    }

    /**
     * 使用 bcrypt 算法对明文密码进行哈希处理。
     *
     * @param plain 明文密码，不能为 {@code null} 或空
     * @return bcrypt 哈希后的密码字符串
     * @throws IllegalArgumentException 如果密码为 {@code null} 或空字符串
     */
    public static String hash(String plain) {
        if (plain == null || plain.isEmpty()) {
            throw new IllegalArgumentException("password is required");
        }
        return BCrypt.hashpw(plain, BCrypt.gensalt());
    }

    /**
     * 返回密码的来源类型。
     *
     * @return {@link Source#PLAIN} 或 {@link Source#HASHED}
     */
    public Source source() {
        return source;
    }

    /**
     * 返回已编码（哈希后）的密码字符串。
     *
     * @return bcrypt 哈希格式的密码字符串
     */
    public String encoded() {
        return encoded;
    }

    /**
     * 验证密码输入的有效性。
     * <p>
     * 检查来源类型和编码后的密码值是否有效。
     *
     * @throws IllegalArgumentException 如果来源类型为 {@code null} 或编码密码为空
     */
    public void validate() {
        if (source == null) {
            throw new IllegalArgumentException("invalid password source");
        }
        if (encoded == null || encoded.isEmpty()) {
            throw new IllegalArgumentException("password is required");
        }
    }

    /**
     * 获取用于网络传输的密码值（即已哈希的密码字符串）。
     * <p>
     * 在返回之前会自动调用 {@link #validate()} 验证输入的有效性。
     *
     * @return 可用于网络传输的 bcrypt 哈希密码字符串
     * @throws IllegalArgumentException 如果密码输入无效
     */
    public String wireValue() {
        validate();
        return encoded;
    }

    /**
     * 判断密码是否已经过哈希处理。
     *
     * @return 如果编码后的密码值非空则返回 {@code true}
     */
    public boolean isHashed() {
        return encoded != null && !encoded.isEmpty();
    }

    /**
     * 判断此密码输入是否为空值（未设置）。
     *
     * @return 如果来源类型为 {@code null} 且编码密码为 {@code null} 或空则返回 {@code true}
     */
    public boolean isZero() {
        return source == null && (encoded == null || encoded.isEmpty());
    }
}
