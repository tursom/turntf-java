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
 * Blocking HTTP transport for turntf's management and query endpoints.
 *
 * <p>This client keeps the public API byte-oriented even when the REST surface uses embedded JSON
 * or base64 fields, so callers can move between HTTP and websocket code paths without reshaping
 * their domain model.
 */
public class TurntfHttpClient {
    private static final MediaType JSON = MediaType.get("application/json");

    private final String baseUrl;
    private final OkHttpClient client;

    public TurntfHttpClient(String baseUrl) {
        this(baseUrl, null);
    }

    public TurntfHttpClient(String baseUrl, OkHttpClient client) {
        Validation.validateBaseUrl(baseUrl);
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.client = client == null ? new OkHttpClient() : client;
    }

    public String baseUrl() {
        return baseUrl;
    }

    /**
     * Performs HTTP login with a plaintext password that is bcrypt-hashed client-side before
     * transmission.
     */
    public String login(long nodeId, long userId, String password) {
        return loginWithPassword(nodeId, userId, PasswordInput.plain(password));
    }

    /**
     * Performs HTTP login with a {@code login_name} selector.
     *
     * <p>This is the newer authentication path added alongside the legacy {@code node_id +
     * user_id} flow. {@code username} is not accepted here because it is display metadata only.
     */
    public String login(String loginName, String password) {
        return loginWithPassword(loginName, PasswordInput.plain(password));
    }

    /**
     * Performs HTTP login with a prebuilt password payload.
     *
     * <p>Callers that already hold a bcrypt hash can use this overload to avoid rehashing.
     */
    public String loginWithPassword(long nodeId, long userId, PasswordInput password) {
        return loginWithPassword(new Credentials(nodeId, userId, password));
    }

    /**
     * Performs HTTP login with a prebuilt password payload and a {@code login_name} selector.
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

    public User createChannel(String token, CreateUserRequest request) {
        return createUser(token, new CreateUserRequest(
            request.username(),
            request.loginName(),
            request.password(),
            request.profileJson(),
            request.role() == null || request.role().isEmpty() ? "channel" : request.role()
        ));
    }

    public void createSubscription(String token, UserRef user, UserRef channel) {
        upsertAttachment(token, user, channel, AttachmentType.CHANNEL_SUBSCRIPTION, "{}".getBytes());
    }

    public List<Message> listMessages(String token, UserRef target, int limit) {
        Validation.validateUserRef(target, "target");
        String path = limit > 0
            ? "/nodes/%d/users/%d/messages?limit=%d".formatted(target.nodeId(), target.userId(), limit)
            : "/nodes/%d/users/%d/messages".formatted(target.nodeId(), target.userId());
        return JsonCodec.messages(doJson("GET", path, token, null, 200));
    }

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

    public List<ClusterNode> listClusterNodes(String token) {
        return JsonCodec.clusterNodes(doJson("GET", "/cluster/nodes", token, null, 200));
    }

    public List<LoggedInUser> listNodeLoggedInUsers(String token, long nodeId) {
        Validation.requirePositive(nodeId, "nodeId");
        return JsonCodec.loggedInUsers(doJson("GET", "/cluster/nodes/%d/logged-in-users".formatted(nodeId), token, null, 200));
    }

    /**
     * Reads a single private metadata entry for the given user.
     */
    public UserMetadata getUserMetadata(String token, UserRef owner, String key) {
        Validation.validateUserRef(owner, "owner");
        Validation.validateUserMetadataKey(key, "key");
        return JsonCodec.userMetadata(doJson("GET", metadataPath(owner, key), token, null, 200));
    }

    /**
     * Creates or replaces a private metadata entry for the given user.
     *
     * <p>The HTTP API represents {@code value} as base64 inside JSON, while the Java surface keeps
     * it as raw bytes so callers can share the same model with the websocket client.
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
     * Creates or replaces a private metadata entry with no expiration.
     */
    public UserMetadata upsertUserMetadata(String token, UserRef owner, String key, byte[] value) {
        return upsertUserMetadata(token, owner, key, value, null);
    }

    /**
     * Deletes a private metadata entry and returns the tombstoned record echoed by the server.
     */
    public UserMetadata deleteUserMetadata(String token, UserRef owner, String key) {
        Validation.validateUserRef(owner, "owner");
        Validation.validateUserMetadataKey(key, "key");
        return JsonCodec.userMetadata(doJson("DELETE", metadataPath(owner, key), token, null, 200));
    }

    /**
     * Scans private metadata keys in ascending order.
     *
     * <p>{@code prefix} and {@code after} are optional cursor filters. {@code limit == 0} leaves
     * page sizing up to the server default.
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

    public BlacklistEntry blockUser(String token, UserRef owner, UserRef blocked) {
        return JsonCodec.blacklistEntry(upsertAttachment(token, owner, blocked, AttachmentType.USER_BLACKLIST, "{}".getBytes()));
    }

    public BlacklistEntry unblockUser(String token, UserRef owner, UserRef blocked) {
        return JsonCodec.blacklistEntry(deleteAttachment(token, owner, blocked, AttachmentType.USER_BLACKLIST));
    }

    public List<BlacklistEntry> listBlockedUsers(String token, UserRef owner) {
        return listAttachments(token, owner, AttachmentType.USER_BLACKLIST).stream().map(JsonCodec::blacklistEntry).toList();
    }

    /**
     * Creates or updates an attachment document through the REST API.
     *
     * <p>The public SDK surface accepts raw bytes for {@code configJson}, but the REST transport
     * requires that JSON to be embedded into the surrounding request body. This method performs
     * that normalization at the boundary.
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

    public List<Attachment> listAttachments(String token, UserRef owner, AttachmentType attachmentType) {
        Validation.validateUserRef(owner, "owner");
        String path = "/nodes/%d/users/%d/attachments".formatted(owner.nodeId(), owner.userId());
        if (attachmentType != null) {
            path += "?attachment_type=" + attachmentType.wireValue();
        }
        return JsonCodec.attachments(doJson("GET", path, token, null, 200));
    }

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
