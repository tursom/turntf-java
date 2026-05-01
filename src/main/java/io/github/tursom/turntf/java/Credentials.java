package io.github.tursom.turntf.java;

/**
 * Login selector shared by the HTTP and websocket clients.
 *
 * <p>Exactly one selector must be provided: either the legacy {@code nodeId + userId} pair or the
 * newer {@code loginName}. This keeps old callers working while exposing the server's dual-track
 * authentication model through one public type.
 *
 * @param nodeId legacy login node identifier; must be paired with {@code userId} when used
 * @param userId legacy login user identifier; must be paired with {@code nodeId} when used
 * @param loginName optional login-name selector for the new authentication path
 * @param password hashed password payload sent to the server
 */
public record Credentials(long nodeId, long userId, String loginName, PasswordInput password) {
    public Credentials {
        loginName = loginName == null ? "" : loginName.trim();
        boolean hasUserSelector = nodeId > 0 || userId > 0;
        boolean hasLoginNameSelector = !loginName.isEmpty();
        if (hasUserSelector) {
            requirePositive(nodeId, "nodeId");
            requirePositive(userId, "userId");
        }
        if (hasUserSelector == hasLoginNameSelector) {
            throw new IllegalArgumentException("exactly one of (nodeId,userId) or loginName must be provided");
        }
        if (password == null) {
            throw new IllegalArgumentException("password is required");
        }
        password.validate();
    }

    public Credentials(long nodeId, long userId, PasswordInput password) {
        this(nodeId, userId, "", password);
    }

    public Credentials(String loginName, PasswordInput password) {
        this(0, 0, loginName, password);
    }

    public boolean hasUserSelector() {
        return nodeId > 0 && userId > 0;
    }

    public boolean hasLoginNameSelector() {
        return !loginName.isEmpty();
    }

    public UserRef user() {
        return hasUserSelector() ? new UserRef(nodeId, userId) : new UserRef(0, 0);
    }

    private static void requirePositive(long value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
