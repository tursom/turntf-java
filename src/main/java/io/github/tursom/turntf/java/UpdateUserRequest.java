package io.github.tursom.turntf.java;

/**
 * Patch-style user update payload.
 *
 * <p>Null fields are omitted. For {@code loginName}, {@code null} keeps the current binding while
 * the empty string requests an explicit unbind.
 *
 * @param username replacement display name; {@code null} leaves it unchanged
 * @param loginName replacement login-name alias; {@code null} keeps it, {@code ""} unbinds it
 * @param password replacement password payload; {@code null} leaves it unchanged
 * @param profileJson replacement profile JSON bytes; {@code null} leaves it unchanged
 * @param role replacement role; {@code null} leaves it unchanged
 */
public record UpdateUserRequest(
    String username,
    String loginName,
    PasswordInput password,
    byte[] profileJson,
    String role
) {
    public UpdateUserRequest(String username, PasswordInput password, byte[] profileJson, String role) {
        this(username, null, password, profileJson, role);
    }
}
