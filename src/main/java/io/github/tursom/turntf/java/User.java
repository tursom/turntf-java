package io.github.tursom.turntf.java;

/**
 * Public user document returned by turntf.
 *
 * <p>{@code username} is display metadata, while {@code loginName} is the optional authentication
 * alias used by the new dual-track login flow.
 *
 * @param nodeId owning node identifier
 * @param userId logical user identifier within {@code nodeId}
 * @param username display name that does not participate in authentication
 * @param role server-side role label
 * @param profileJson raw profile payload as JSON bytes
 * @param systemReserved whether the user is reserved by the system
 * @param createdAt creation timestamp echoed by the server
 * @param updatedAt last update timestamp echoed by the server
 * @param originNodeId source node that originated the current record
 * @param loginName optional login-name alias used for authentication
 */
public record User(
    long nodeId,
    long userId,
    String username,
    String role,
    byte[] profileJson,
    boolean systemReserved,
    String createdAt,
    String updatedAt,
    long originNodeId,
    String loginName
) {
    public User(long nodeId, long userId, String username, String role, byte[] profileJson, boolean systemReserved, String createdAt, String updatedAt, long originNodeId) {
        this(nodeId, userId, username, role, profileJson, systemReserved, createdAt, updatedAt, originNodeId, "");
    }
}
