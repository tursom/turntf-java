package io.github.tursom.turntf.java;

/**
 * 可通讯用户列表过滤条件。
 * <p>
 * 该过滤对象同时用于 {@link TurntfHttpClient#listUsers(String, ListUsersFilter)} 和
 * {@link TurntfClient#listUsers(ListUsersFilter)}，但两条传输路径对 {@code uid} 的线缆表示不同：
 * HTTP 会把非零 {@link UserRef} 编码成 {@code node_id:user_id} 查询字符串，而 protobuf RPC
 * 会把它编码进 {@code ListUsersRequest.uid} 的 {@code UserRef} 字段。
 * <p>
 * {@code uid == null} 或 {@code uid == new UserRef(0, 0)} 都表示“不按 uid 过滤”；如果只填写
 * 其中一个坐标，SDK 会在发请求前直接拒绝该参数，避免把半空的选择器送到服务端。
 * <p>
 * 服务端返回的是“当前用户可通讯的活跃用户集合”，而不是系统全量用户。普通用户查看他人时，
 * {@link User#loginName()} 可能被服务端脱敏为空字符串；管理员或查看自己时保持可见。
 *
 * @param name 可选名称过滤；服务端按大小写不敏感子串匹配
 * @param uid  可选精确 uid 过滤；非空时表示一个具体的用户引用
 */
public record ListUsersFilter(
    String name,
    UserRef uid
) {
    private static final ListUsersFilter EMPTY = new ListUsersFilter(null, null);

    /**
     * 返回不带任何过滤条件的空过滤器。
     */
    public static ListUsersFilter empty() {
        return EMPTY;
    }

    public ListUsersFilter(String name) {
        this(name, null);
    }

    public ListUsersFilter(UserRef uid) {
        this(null, uid);
    }
}
