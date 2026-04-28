package io.github.tursom.turntf.java;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.tursom.turntf.java.internal.JsonCodec;
import io.github.tursom.turntf.java.internal.Validation;
import java.io.IOException;
import java.util.List;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

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

    public String login(long nodeId, long userId, String password) {
        return loginWithPassword(nodeId, userId, PasswordInput.plain(password));
    }

    public String loginWithPassword(long nodeId, long userId, PasswordInput password) {
        Validation.requirePositive(nodeId, "nodeId");
        Validation.requirePositive(userId, "userId");
        ObjectNode payload = JsonCodec.object();
        payload.put("node_id", nodeId);
        payload.put("user_id", userId);
        payload.put("password", password.wireValue());
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

    public BlacklistEntry blockUser(String token, UserRef owner, UserRef blocked) {
        return JsonCodec.blacklistEntry(upsertAttachment(token, owner, blocked, AttachmentType.USER_BLACKLIST, "{}".getBytes()));
    }

    public BlacklistEntry unblockUser(String token, UserRef owner, UserRef blocked) {
        return JsonCodec.blacklistEntry(deleteAttachment(token, owner, blocked, AttachmentType.USER_BLACKLIST));
    }

    public List<BlacklistEntry> listBlockedUsers(String token, UserRef owner) {
        return listAttachments(token, owner, AttachmentType.USER_BLACKLIST).stream().map(JsonCodec::blacklistEntry).toList();
    }

    public Attachment upsertAttachment(String token, UserRef owner, UserRef subject, AttachmentType attachmentType, byte[] configJson) {
        Validation.validateUserRef(owner, "owner");
        Validation.validateUserRef(subject, "subject");
        ObjectNode payload = JsonCodec.object();
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
                String data = response.body() == null ? "" : response.body().string().trim();
                throw new ProtocolError("unexpected HTTP status " + response.code() + ": " + data);
            }
            return JsonCodec.read(response.body());
        } catch (IOException e) {
            throw new ConnectionError(method + " " + path, e);
        }
    }
}
