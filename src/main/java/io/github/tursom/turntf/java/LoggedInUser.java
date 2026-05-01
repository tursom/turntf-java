package io.github.tursom.turntf.java;

/**
 * Online user snapshot returned by cluster presence endpoints.
 *
 * @param nodeId node currently reporting the online user
 * @param userId online user identifier
 * @param username display name echoed by the server
 * @param loginName optional login-name alias currently bound to the user
 */
public record LoggedInUser(long nodeId, long userId, String username, String loginName) {
    public LoggedInUser(long nodeId, long userId, String username) {
        this(nodeId, userId, username, "");
    }
}
