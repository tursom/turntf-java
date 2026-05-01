package io.github.tursom.turntf.java;

/**
 * User creation payload shared by the HTTP and websocket clients.
 *
 * <p>{@code username} does not participate in authentication. When {@code loginName} is present,
 * the created user can also log in via {@code login_name + password}.
 *
 * @param username display name for the new user
 * @param loginName optional login-name alias bound at creation time
 * @param password optional password payload; when omitted the server decides whether login is allowed
 * @param profileJson optional profile JSON bytes
 * @param role requested server-side role
 */
public record CreateUserRequest(
    String username,
    String loginName,
    PasswordInput password,
    byte[] profileJson,
    String role
) {
    public CreateUserRequest(String username, PasswordInput password, byte[] profileJson, String role) {
        this(username, null, password, profileJson, role);
    }
}
