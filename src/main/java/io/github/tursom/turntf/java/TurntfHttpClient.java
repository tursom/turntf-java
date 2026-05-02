package io.github.tursom.turntf.java;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.tursom.turntf.java.internal.JsonCodec;
import io.github.tursom.turntf.java.internal.Validation;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * turntf 管理端点和查询端点的阻塞式 HTTP 传输客户端。
 * <p>
 * 该客户端通过 HTTP REST API 与 turntf 服务器通信，提供用户管理、消息收发、
 * 附件管理、元数据管理等功能的同步调用接口。
 * <p>
 * 即使 REST 接口内部使用 JSON 或 Base64 编码字段，该客户端保持对外 API 面向字节，
 * 使得调用方可以在 HTTP 和 WebSocket 两种传输路径之间切换，而无需重塑其领域模型。
 * <p>
 * <b>使用示例：</b>
 * <pre>{@code
 * TurntfHttpClient client = new TurntfHttpClient("http://localhost:8080");
 * String token = client.login(1, 100, "myPassword");
 * User user = client.createUser(token, new CreateUserRequest("alice", ...));
 * }</pre>
 */
public class TurntfHttpClient {
    private static final MediaType JSON = MediaType.get("application/json");

    private final String baseUrl;
    private final OkHttpClient client;

    /**
     * 使用指定的基础 URL 创建客户端实例。
     * <p>
     * 内部会创建一个默认的 {@link OkHttpClient} 实例。
     *
     * @param baseUrl 服务器的 HTTP 基础 URL，例如 "http://localhost:8080"
     */
    public TurntfHttpClient(String baseUrl) {
        this(baseUrl, null);
    }

    /**
     * 使用指定的基础 URL 和 HTTP 客户端创建实例。
     * <p>
     * 允许调用方传入自定义配置的 {@link OkHttpClient}，例如配置超时、代理、TLS 等。
     *
     * @param baseUrl 服务器的 HTTP 基础 URL
     * @param client  自定义的 OkHttpClient 实例；如果为 {@code null} 则创建默认实例
     */
    public TurntfHttpClient(String baseUrl, OkHttpClient client) {
        Validation.validateBaseUrl(baseUrl);
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.client = client == null ? new OkHttpClient() : client;
    }

    /**
     * 返回客户端的 HTTP 基础 URL。
     *
     * @return 基础 URL 字符串，末尾不包含斜杠
     */
    public String baseUrl() {
        return baseUrl;
    }

    /**
     * 使用明文密码通过 HTTP 登录（旧版 nodeId + userId 方式）。
     * <p>
     * 密码会在客户端使用 bcrypt 哈希后再传输。
     *
     * @param nodeId   节点标识
     * @param userId   用户标识
     * @param password 明文密码
     * @return 登录成功后的认证令牌（JWT Token）
     */
    public String login(long nodeId, long userId, String password) {
        return loginWithPassword(nodeId, userId, PasswordInput.plain(password));
    }

    /**
     * 使用登录名和明文密码通过 HTTP 登录（新版双轨登录流程）。
     * <p>
     * 这是与旧版 {@code nodeId + userId} 流程并存的新认证方式。
     * 注意此处不支持使用 {@code username}（显示用户名）登录，因为 username 仅为展示性元数据。
     *
     * @param loginName 登录名（在创建用户时指定的认证别名）
     * @param password  明文密码
     * @return 登录成功后的认证令牌（JWT Token）
     */
    public String login(String loginName, String password) {
        return loginWithPassword(loginName, PasswordInput.plain(password));
    }

    /**
     * 使用预构建的密码输入对象通过 HTTP 登录（旧版 nodeId + userId 方式）。
     * <p>
     * 如果调用方已经持有 bcrypt 哈希值，可以使用此重载方法避免重复哈希。
     *
     * @param nodeId   节点标识
     * @param userId   用户标识
     * @param password 密码输入对象（可使用 {@link PasswordInput#plain(String)} 或 {@link PasswordInput#hashed(String)} 创建）
     * @return 登录成功后的认证令牌（JWT Token）
     */
    public String loginWithPassword(long nodeId, long userId, PasswordInput password) {
        return loginWithPassword(new Credentials(nodeId, userId, password));
    }

    /**
     * 使用预构建的密码输入对象和登录名通过 HTTP 登录（新版双轨登录流程）。
     *
     * @param loginName 登录名
     * @param password  密码输入对象
     * @return 登录成功后的认证令牌（JWT Token）
     */
    public String loginWithPassword(String loginName, PasswordInput password) {
        return loginWithPassword(new Credentials(loginName, password));
    }

    private String loginWithPassword(Credentials credentials) {
        ObjectNode payload = JsonCodec.object();
        if (credentials.hasUserSelector()) {
            payload.put("node_id", credentials.nodeId());
            payload.put("user_id", credentials.userId());
        } else {
            payload.put("login_name", credentials.loginName());
        }
        payload.put("password", credentials.password().wireValue());
        JsonNode response = doJson("POST", "/auth/login", "", payload, 200);
        String token = JsonCodec.text(response, "token");
        if (token.isEmpty()) {
            throw new ProtocolError("empty token in login response");
        }
        return token;
    }

    /**
     * 创建新用户。
     *
     * @param token   认证令牌
     * @param request 创建用户请求参数，包含用户名、角色、可选密码和配置信息
     * @return 创建成功的用户对象
     * @throws IllegalArgumentException 如果用户名为空或角色为空
     */
    public User createUser(String token, CreateUserRequest request) {
        if (request.username() == null || request.username().isEmpty()) {
            throw new IllegalArgumentException("username is required");
        }
        if (request.role() == null || request.role().isEmpty()) {
            throw new IllegalArgumentException("role is required");
        }
        ObjectNode payload = JsonCodec.object();
        payload.put("username", request.username());
        payload.put("role", request.role());
        if (request.loginName() != null) {
            payload.put("login_name", request.loginName());
        }
        if (request.password() != null) {
            payload.put("password", request.password().wireValue());
        }
        if (request.profileJson() != null && request.profileJson().length > 0) {
            payload.set("profile", JsonCodec.parseBytesAsJson(request.profileJson()));
        }
        return JsonCodec.user(doJson("POST", "/users", token, payload, 200, 201));
    }

    /**
     * 创建频道（一种特殊类型的用户）。
     * <p>
     * 如果请求中未指定角色，则默认使用 "channel" 角色。
     *
     * @param token   认证令牌
     * @param request 创建用户请求参数
     * @return 创建成功的频道用户对象
     */
    public User createChannel(String token, CreateUserRequest request) {
        return createUser(token, new CreateUserRequest(
            request.username(),
            request.loginName(),
            request.password(),
            request.profileJson(),
            request.role() == null || request.role().isEmpty() ? "channel" : request.role()
        ));
    }

    /**
     * 创建用户对频道的订阅关系。
     * <p>
     * 此操作会在用户和频道之间创建一个 {@link AttachmentType#CHANNEL_SUBSCRIPTION} 类型的附件。
     *
     * @param token   认证令牌
     * @param user    订阅者用户引用
     * @param channel 被订阅的频道用户引用
     */
    public void createSubscription(String token, UserRef user, UserRef channel) {
        upsertAttachment(token, user, channel, AttachmentType.CHANNEL_SUBSCRIPTION, "{}".getBytes());
    }

    /**
     * 获取指定用户的消息列表。
     * <p>
     * 此重载允许 {@code target} 的 nodeId/userId 为 0，表示以当前登录用户身份查询。
     *
     * @param token  认证令牌
     * @param target 目标用户引用（nodeId/userId 可为 0，表示 "当前用户"）
     * @param limit  返回消息的最大数量；如果为 0 或负数则使用服务端默认值
     * @return 消息列表，按时间顺序排列
     */
    public List<Message> listMessages(String token, UserRef target, int limit) {
        return listMessages(token, target, limit, null, null);
    }

    /**
     * 获取指定用户的消息列表，支持按会话对端过滤。
     * <p>
     * 当同时提供了 {@code peerNodeId} 和 {@code peerUserId} 时，会在查询字符串中追加
     * {@code peer_node_id} 和 {@code peer_user_id} 参数，服务端据此过滤出与该对端的会话消息。
     * <p>
     * 此方法允许 {@code target} 的 nodeId/userId 为 0，表示以当前登录用户身份查询。
     *
     * @param token      认证令牌
     * @param target     目标用户引用（nodeId/userId 可为 0，表示 "当前用户"）
     * @param limit      返回消息的最大数量；如果为 0 或负数则使用服务端默认值
     * @param peerNodeId 可选的会话对端节点标识；与 {@code peerUserId} 同时提供时按会话过滤
     * @param peerUserId 可选的会话对端用户标识；与 {@code peerNodeId} 同时提供时按会话过滤
     * @return 消息列表，按时间顺序排列
     */
    public List<Message> listMessages(String token, UserRef target, int limit, Long peerNodeId, Long peerUserId) {
        StringBuilder path = new StringBuilder("/nodes/")
            .append(target.nodeId()).append("/users/").append(target.userId()).append("/messages");
        boolean hasQuery = false;
        if (limit > 0) {
            hasQuery = appendQuery(path, false, "limit", Integer.toString(limit));
        }
        if (peerNodeId != null && peerUserId != null) {
            hasQuery = appendQuery(path, hasQuery, "peer_node_id", Long.toString(peerNodeId));
            appendQuery(path, hasQuery, "peer_user_id", Long.toString(peerUserId));
        }
        return JsonCodec.messages(doJson("GET", path.toString(), token, null, 200));
    }

    /**
     * 向目标用户发送消息。
     * <p>
     * 消息体以原始字节数组形式提交。Jackson 将 byte[] 序列化为 Base64，
     * 这与 HTTP API 的 JSON 表示一致。WebSocket/Protobuf 客户端路径则发送原始字节。
     *
     * @param token  认证令牌
     * @param target 目标用户引用
     * @param body   消息体的原始字节数据
     * @return 服务器返回的已创建消息对象
     * @throws IllegalArgumentException 如果消息体为 {@code null} 或空数组
     */
    public Message postMessage(String token, UserRef target, byte[] body) {
        Validation.validateUserRef(target, "target");
        if (body == null || body.length == 0) {
            throw new IllegalArgumentException("body is required");
        }
        ObjectNode payload = JsonCodec.object();
        // Jackson serializes byte[] as base64, matching the HTTP API's JSON representation for
        // opaque message bodies. The websocket/protobuf client path sends raw bytes instead.
        payload.put("body", body);
        return JsonCodec.message(doJson("POST", "/nodes/%d/users/%d/messages".formatted(target.nodeId(), target.userId()), token, payload, 200, 201));
    }

    /**
     * 向目标用户发送数据包（瞬态中继消息）。
     * <p>
     * 数据包中继在 HTTP 传输中复用消息端点，因此投递类型和模式需要在 JSON 中显式编码，
     * 而不是像 {@link TurntfClient} 那样依赖 Protobuf oneof 结构。
     *
     * @param token         认证令牌
     * @param targetNodeId  目标节点标识
     * @param relayTarget   中继目标用户引用
     * @param body          数据包体的原始字节数据
     * @param mode          投递模式（如 DIRECT、RELAY 等）
     * @throws IllegalArgumentException 如果参数校验失败
     */
    public void postPacket(String token, long targetNodeId, UserRef relayTarget, byte[] body, DeliveryMode mode) {
        Validation.requirePositive(targetNodeId, "targetNodeId");
        Validation.validateUserRef(relayTarget, "relayTarget");
        if (targetNodeId != relayTarget.nodeId()) {
            throw new IllegalArgumentException("target node ID %d does not match target user nodeId %d".formatted(targetNodeId, relayTarget.nodeId()));
        }
        if (body == null || body.length == 0) {
            throw new IllegalArgumentException("body is required");
        }
        Validation.validateDeliveryMode(mode);
        ObjectNode payload = JsonCodec.object();
        payload.put("body", body);
        // HTTP packet relay reuses the message endpoint, so delivery kind/mode have to be encoded
        // explicitly in JSON instead of relying on the protobuf oneof shape used by TurntfClient.
        payload.put("delivery_kind", "transient");
        payload.put("delivery_mode", mode.wireValue());
        doJson("POST", "/nodes/%d/users/%d/messages".formatted(relayTarget.nodeId(), relayTarget.userId()), token, payload, 202);
    }

    /**
     * 获取集群中的所有节点列表。
     *
     * @param token 认证令牌
     * @return 集群节点列表
     */
    public List<ClusterNode> listClusterNodes(String token) {
        return JsonCodec.clusterNodes(doJson("GET", "/cluster/nodes", token, null, 200));
    }

    /**
     * 获取指定节点上当前已登录的用户列表。
     *
     * @param token  认证令牌
     * @param nodeId 节点标识
     * @return 已登录用户列表
     */
    public List<LoggedInUser> listNodeLoggedInUsers(String token, long nodeId) {
        Validation.requirePositive(nodeId, "nodeId");
        return JsonCodec.loggedInUsers(doJson("GET", "/cluster/nodes/%d/logged-in-users".formatted(nodeId), token, null, 200));
    }

    /**
     * 读取指定用户的单条私有元数据条目。
     *
     * @param token 认证令牌
     * @param owner 元数据所属的用户引用
     * @param key   元数据的键名
     * @return 元数据条目
     */
    public UserMetadata getUserMetadata(String token, UserRef owner, String key) {
        Validation.validateUserRef(owner, "owner");
        Validation.validateUserMetadataKey(key, "key");
        return JsonCodec.userMetadata(doJson("GET", metadataPath(owner, key), token, null, 200));
    }

    /**
     * 创建或替换指定用户的私有元数据条目（带过期时间）。
     * <p>
     * HTTP API 在 JSON 内部将 {@code value} 表示为 Base64 编码，
     * 而 Java 接口保持为原始字节，使调用方可以共享与 WebSocket 客户端相同的模型。
     *
     * @param token      认证令牌
     * @param owner      元数据所属的用户引用
     * @param key        元数据的键名
     * @param value      元数据的值（原始字节），如果为 {@code null} 则写入空数组
     * @param expiresAt  过期时间字符串；如果为 {@code null} 则表示永不过期
     * @return 创建或更新后的元数据条目
     */
    public UserMetadata upsertUserMetadata(String token, UserRef owner, String key, byte[] value, String expiresAt) {
        Validation.validateUserRef(owner, "owner");
        Validation.validateUserMetadataKey(key, "key");
        ObjectNode payload = JsonCodec.object();
        payload.put("value", value == null ? new byte[0] : value);
        if (expiresAt != null) {
            payload.put("expires_at", expiresAt);
        }
        return JsonCodec.userMetadata(doJson("PUT", metadataPath(owner, key), token, payload, 200, 201));
    }

    /**
     * 创建或替换指定用户的私有元数据条目（无过期时间）。
     *
     * @param token 认证令牌
     * @param owner 元数据所属的用户引用
     * @param key   元数据的键名
     * @param value 元数据的值（原始字节）
     * @return 创建或更新后的元数据条目
     */
    public UserMetadata upsertUserMetadata(String token, UserRef owner, String key, byte[] value) {
        return upsertUserMetadata(token, owner, key, value, null);
    }

    /**
     * 删除指定用户的私有元数据条目。
     * <p>
     * 服务器会返回已删除（标记为墓碑）的记录作为回显。
     *
     * @param token 认证令牌
     * @param owner 元数据所属的用户引用
     * @param key   要删除的元数据键名
     * @return 已删除的元数据条目（由服务器回显）
     */
    public UserMetadata deleteUserMetadata(String token, UserRef owner, String key) {
        Validation.validateUserRef(owner, "owner");
        Validation.validateUserMetadataKey(key, "key");
        return JsonCodec.userMetadata(doJson("DELETE", metadataPath(owner, key), token, null, 200));
    }

    /**
     * 按升序遍历用户私有元数据键空间（支持分页）。
     * <p>
     * {@code prefix} 和 {@code after} 是可选的游标过滤器。
     * 当 {@code limit == 0} 时，分页大小由服务器默认值决定。
     * <p>
     * 返回结果中的 {@link UserMetadataScanResult#nextAfter()} 值可作为下一次查询的 {@code after} 参数传入，
     * 以获取下一页数据。
     *
     * @param token  认证令牌
     * @param owner  元数据所属的用户引用
     * @param prefix 可选的前缀过滤条件，只返回键名以此前缀开头的条目；为 {@code null} 或空则不限制
     * @param after  可选的游标参数，只返回键名在此之后的条目；为 {@code null} 或空则从头开始
     * @param limit  每页最大条目数；{@code 0} 表示使用服务器默认值
     * @return 扫描结果，包含当前页条目和下一页游标
     */
    public UserMetadataScanResult scanUserMetadata(String token, UserRef owner, String prefix, String after, int limit) {
        Validation.validateUserRef(owner, "owner");
        Validation.validateUserMetadataScan(prefix, after, limit);
        StringBuilder path = new StringBuilder("/nodes/%d/users/%d/metadata".formatted(owner.nodeId(), owner.userId()));
        boolean hasQuery = false;
        hasQuery = appendQuery(path, hasQuery, "prefix", prefix);
        hasQuery = appendQuery(path, hasQuery, "after", after);
        if (limit > 0) {
            appendQuery(path, hasQuery, "limit", Integer.toString(limit));
        }
        return JsonCodec.userMetadataScanResult(doJson("GET", path.toString(), token, null, 200));
    }

    /**
     * 将指定用户加入黑名单。
     * <p>
     * 在用户和黑名单目标之间创建 {@link AttachmentType#USER_BLACKLIST} 类型的附件。
     *
     * @param token   认证令牌
     * @param owner   执行屏蔽操作的用户引用
     * @param blocked 被屏蔽的用户引用
     * @return 黑名单条目
     */
    public BlacklistEntry blockUser(String token, UserRef owner, UserRef blocked) {
        return JsonCodec.blacklistEntry(upsertAttachment(token, owner, blocked, AttachmentType.USER_BLACKLIST, "{}".getBytes()));
    }

    /**
     * 将指定用户移出黑名单。
     * <p>
     * 删除用户和黑名单目标之间的 {@link AttachmentType#USER_BLACKLIST} 类型的附件。
     *
     * @param token   认证令牌
     * @param owner   执行解除屏蔽操作的用户引用
     * @param blocked 被解除屏蔽的用户引用
     * @return 已删除的黑名单条目（由服务器回显）
     */
    public BlacklistEntry unblockUser(String token, UserRef owner, UserRef blocked) {
        return JsonCodec.blacklistEntry(deleteAttachment(token, owner, blocked, AttachmentType.USER_BLACKLIST));
    }

    /**
     * 获取指定用户的黑名单列表。
     *
     * @param token 认证令牌
     * @param owner 要查询黑名单的用户引用
     * @return 黑名单条目列表
     */
    public List<BlacklistEntry> listBlockedUsers(String token, UserRef owner) {
        return listAttachments(token, owner, AttachmentType.USER_BLACKLIST).stream().map(JsonCodec::blacklistEntry).toList();
    }

    /**
     * 创建或更新附件文档。
     * <p>
     * 公开 SDK 接口接受原始字节作为 {@code configJson} 参数，
     * 但 REST 传输要求该 JSON 嵌入到外围的请求体中。此方法在边界处完成该规范化转换。
     *
     * @param token          认证令牌
     * @param owner          附件的所有者用户引用
     * @param subject        附件关联的目标用户引用
     * @param attachmentType 附件类型
     * @param configJson     附件配置数据的 JSON 字节数组（会被解析为 JSON 对象嵌入请求体）
     * @return 创建或更新后的附件记录
     */
    public Attachment upsertAttachment(String token, UserRef owner, UserRef subject, AttachmentType attachmentType, byte[] configJson) {
        Validation.validateUserRef(owner, "owner");
        Validation.validateUserRef(subject, "subject");
        ObjectNode payload = JsonCodec.object();
        // The REST API exposes config_json as embedded JSON, not as base64 bytes. Parsing here
        // keeps HTTP callers aligned with the server's canonical attachment document shape.
        payload.set("config_json", JsonCodec.parseBytesAsJson(configJson));
        return JsonCodec.attachment(doJson(
            "PUT",
            "/nodes/%d/users/%d/attachments/%s/%d/%d".formatted(owner.nodeId(), owner.userId(), attachmentType.wireValue(), subject.nodeId(), subject.userId()),
            token,
            payload,
            200,
            201
        ));
    }

    /**
     * 删除附件文档。
     *
     * @param token          认证令牌
     * @param owner          附件的所有者用户引用
     * @param subject        附件关联的目标用户引用
     * @param attachmentType 附件类型
     * @return 已删除的附件记录（由服务器回显）
     */
    public Attachment deleteAttachment(String token, UserRef owner, UserRef subject, AttachmentType attachmentType) {
        Validation.validateUserRef(owner, "owner");
        Validation.validateUserRef(subject, "subject");
        return JsonCodec.attachment(doJson(
            "DELETE",
            "/nodes/%d/users/%d/attachments/%s/%d/%d".formatted(owner.nodeId(), owner.userId(), attachmentType.wireValue(), subject.nodeId(), subject.userId()),
            token,
            null,
            200
        ));
    }

    /**
     * 获取指定用户的所有附件列表，可按类型过滤。
     *
     * @param token          认证令牌
     * @param owner          附件的所有者用户引用
     * @param attachmentType 可选的附件类型过滤条件；如果为 {@code null} 则返回所有类型的附件
     * @return 附件记录列表
     */
    public List<Attachment> listAttachments(String token, UserRef owner, AttachmentType attachmentType) {
        Validation.validateUserRef(owner, "owner");
        String path = "/nodes/%d/users/%d/attachments".formatted(owner.nodeId(), owner.userId());
        if (attachmentType != null) {
            path += "?attachment_type=" + attachmentType.wireValue();
        }
        return JsonCodec.attachments(doJson("GET", path, token, null, 200));
    }

    /**
     * 获取指定用户的详细信息。
     *
     * @param token  认证令牌
     * @param target 目标用户引用
     * @return 用户详细信息
     */
    public User getUser(String token, UserRef target) {
        Validation.validateUserRef(target, "target");
        return JsonCodec.user(doJson("GET", "/nodes/%d/users/%d".formatted(target.nodeId(), target.userId()), token, null, 200));
    }

    /**
     * 更新用户信息。仅非 {@code null} 的字段会被更新。
     *
     * @param token   认证令牌
     * @param target  目标用户引用
     * @param request 更新请求，{@code null} 字段表示不修改
     * @return 更新后的用户信息
     */
    public User updateUser(String token, UserRef target, UpdateUserRequest request) {
        Validation.validateUserRef(target, "target");
        if ("channel".equals(request.role()) && request.loginName() != null && !request.loginName().isEmpty()) {
            throw new IllegalArgumentException("channel users cannot have a login_name");
        }
        ObjectNode payload = JsonCodec.object();
        if (request.username() != null) {
            payload.put("username", request.username());
        }
        if (request.loginName() != null) {
            if (request.loginName().isEmpty()) {
                payload.put("login_name", "");
            } else {
                payload.put("login_name", request.loginName().trim());
            }
        }
        if (request.password() != null) {
            payload.put("password", request.password().wireValue());
        }
        if (request.profileJson() != null && request.profileJson().length > 0) {
            payload.set("profile", JsonCodec.parseBytesAsJson(request.profileJson()));
        }
        if (request.role() != null) {
            payload.put("role", request.role());
        }
        return JsonCodec.user(doJson("PATCH", "/nodes/%d/users/%d".formatted(target.nodeId(), target.userId()), token, payload, 200));
    }

    /**
     * 删除指定用户（软删除）。
     *
     * @param token  认证令牌
     * @param target 目标用户引用
     * @return 删除结果，包含操作状态和被删除用户引用
     */
    public DeleteUserResult deleteUser(String token, UserRef target) {
        Validation.validateUserRef(target, "target");
        return JsonCodec.deleteUserResult(doJson("DELETE", "/nodes/%d/users/%d".formatted(target.nodeId(), target.userId()), token, null, 200));
    }

    /**
     * 查询事件日志，支持分页游标。
     *
     * @param token 认证令牌
     * @param after 起始事件序列号（不含），0 表示从头开始
     * @param limit 返回事件的最大数量，0 表示使用服务端默认值
     * @return 事件列表
     */
    public List<Event> listEvents(String token, long after, int limit) {
        StringBuilder path = new StringBuilder("/events");
        boolean hasQuery = false;
        if (after > 0) {
            hasQuery = appendQuery(path, hasQuery, "after", Long.toString(after));
        }
        if (limit > 0) {
            appendQuery(path, hasQuery, "limit", Integer.toString(limit));
        }
        return JsonCodec.events(doJson("GET", path.toString(), token, null, 200));
    }

    /**
     * 查询节点运行状态，包含消息窗口、写闸门、投影等指标。
     *
     * @param token 认证令牌
     * @return 运行状态信息
     */
    public OperationsStatus operationsStatus(String token) {
        return JsonCodec.operationsStatus(doJson("GET", "/ops/status", token, null, 200));
    }

    /**
     * 获取 Prometheus 格式的监控指标文本。
     *
     * @param token 认证令牌
     * @return 监控指标文本（Prometheus 格式）
     */
    public String metrics(String token) {
        return doText("/metrics", token, 200);
    }

    /**
     * 执行 HTTP 请求并返回 JSON 响应。
     * <p>
     * 该方法为受保护的（protected），子类可以重写以实现自定义的请求处理逻辑（如请求日志、鉴权扩展等）。
     *
     * @param method       HTTP 方法（GET、POST、PUT、DELETE 等）
     * @param path         请求路径（相对于 baseUrl）
     * @param token        可选的认证令牌；如果非空会添加 Authorization 头
     * @param requestBody  可选的请求体 JSON 节点；如果为 {@code null} 则发送无体请求
     * @param wantStatuses 期望的 HTTP 状态码列表；如果实际状态码不在列表中则抛出 {@link ProtocolError}
     * @return 解析后的 JSON 响应节点
     * @throws ProtocolError  如果服务器返回的状态码不在 {@code wantStatuses} 中
     * @throws ConnectionError 如果网络请求过程中发生 I/O 异常
     */
    protected JsonNode doJson(String method, String path, String token, JsonNode requestBody, int... wantStatuses) {
        RequestBody body = requestBody == null ? null : RequestBody.create(requestBody.toString(), JSON);
        Request.Builder builder = new Request.Builder().url(baseUrl + path);
        builder.method(method, body);
        if (requestBody != null) {
            builder.header("Content-Type", "application/json");
        }
        if (token != null && !token.isEmpty()) {
            builder.header("Authorization", "Bearer " + token);
        }

        try (Response response = client.newCall(builder.build()).execute()) {
            boolean allowed = false;
            for (int status : wantStatuses) {
                if (response.code() == status) {
                    allowed = true;
                    break;
                }
            }
            if (!allowed) {
                // Bubble up the response body verbatim because protocol mismatches are usually
                // easier to diagnose from the server's JSON error payload than from the status
                // code alone.
                String data = response.body() == null ? "" : response.body().string().trim();
                throw new ProtocolError("unexpected HTTP status " + response.code() + ": " + data);
            }
            return JsonCodec.read(response.body());
        } catch (IOException e) {
            throw new ConnectionError(method + " " + path, e);
        }
    }

    /**
     * 执行 HTTP GET 请求并返回原始文本响应。
     */
    private String doText(String path, String token, int... wantStatuses) {
        Request.Builder builder = new Request.Builder().url(baseUrl + path);
        builder.method("GET", null);
        if (token != null && !token.isEmpty()) {
            builder.header("Authorization", "Bearer " + token);
        }
        try (Response response = client.newCall(builder.build()).execute()) {
            boolean allowed = false;
            for (int status : wantStatuses) {
                if (response.code() == status) {
                    allowed = true;
                    break;
                }
            }
            if (!allowed) {
                String data = response.body() == null ? "" : response.body().string().trim();
                throw new ProtocolError("unexpected HTTP status " + response.code() + ": " + data);
            }
            return response.body() == null ? "" : response.body().string();
        } catch (IOException e) {
            throw new ConnectionError("GET " + path, e);
        }
    }

    private static String metadataPath(UserRef owner, String key) {
        return "/nodes/%d/users/%d/metadata/%s".formatted(owner.nodeId(), owner.userId(), urlEncode(key));
    }

    private static boolean appendQuery(StringBuilder path, boolean hasQuery, String name, String value) {
        if (value == null || value.isEmpty()) {
            return hasQuery;
        }
        path.append(hasQuery ? '&' : '?')
            .append(name)
            .append('=')
            .append(urlEncode(value));
        return true;
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
