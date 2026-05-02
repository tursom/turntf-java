package io.github.tursom.turntf.java;

/**
 * turntf 用户文档，表示系统中的一个用户或频道实体。
 * <p>
 * 用户由 {@code nodeId} 和 {@code userId} 唯一标识。{@code username} 是用于展示的用户名，
 * {@code loginName} 是可选的登录名别名，用于新的双轨登录流程（与旧版 nodeId+userId 认证方式并存）。
 * <p>
 * 频道也是一种特殊类型的用户（通常带有 "channel" 角色），通过相同的 API 创建和管理。
 *
 * @param nodeId         所属节点标识
 * @param userId         在节点内的逻辑用户标识
 * @param username       显示用户名，不参与认证过程
 * @param role           服务端定义的角色标签（如 "user"、"channel"、"admin" 等）
 * @param profileJson    用户配置文件的原始 JSON 字节数据
 * @param systemReserved 是否为系统保留用户
 * @param createdAt      用户创建时间戳（由服务器返回）
 * @param updatedAt      用户最后更新时间戳（由服务器返回）
 * @param originNodeId   创建此用户记录的源节点标识
 * @param loginName      可选的登录名别名，用于用户名/密码方式登录
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
    /**
     * 创建一个不带 {@code loginName} 的用户记录（兼容旧版 API）。
     * <p>
     * 此构造器将 {@code loginName} 默认设为空字符串。
     *
     * @param nodeId         所属节点标识
     * @param userId         在节点内的逻辑用户标识
     * @param username       显示用户名
     * @param role           服务端定义的角色标签
     * @param profileJson    用户配置文件的原始 JSON 字节数据
     * @param systemReserved 是否为系统保留用户
     * @param createdAt      用户创建时间戳
     * @param updatedAt      用户最后更新时间戳
     * @param originNodeId   创建此用户记录的源节点标识
     */
    public User(long nodeId, long userId, String username, String role, byte[] profileJson, boolean systemReserved, String createdAt, String updatedAt, long originNodeId) {
        this(nodeId, userId, username, role, profileJson, systemReserved, createdAt, updatedAt, originNodeId, "");
    }
}
