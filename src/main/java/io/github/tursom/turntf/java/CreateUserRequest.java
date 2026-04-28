package io.github.tursom.turntf.java;

public record CreateUserRequest(
    String username,
    PasswordInput password,
    byte[] profileJson,
    String role
) {
}
