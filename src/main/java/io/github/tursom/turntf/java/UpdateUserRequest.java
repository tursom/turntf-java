package io.github.tursom.turntf.java;

public record UpdateUserRequest(
    String username,
    PasswordInput password,
    byte[] profileJson,
    String role
) {
}
