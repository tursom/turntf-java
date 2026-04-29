package io.github.tursom.turntf.java.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.tursom.turntf.java.Attachment;
import io.github.tursom.turntf.java.AttachmentType;
import io.github.tursom.turntf.java.BlacklistEntry;
import io.github.tursom.turntf.java.ClusterNode;
import io.github.tursom.turntf.java.LoggedInUser;
import io.github.tursom.turntf.java.Message;
import io.github.tursom.turntf.java.Subscription;
import io.github.tursom.turntf.java.User;
import io.github.tursom.turntf.java.UserRef;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import okhttp3.ResponseBody;

public final class JsonCodec {
    public static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonCodec() {
    }

    public static JsonNode read(ResponseBody body) throws IOException {
        if (body == null) {
            return NullNode.getInstance();
        }
        String content = body.string();
        if (content.isBlank()) {
            return NullNode.getInstance();
        }
        return MAPPER.readTree(content);
    }

    public static ObjectNode object() {
        return JsonNodeFactory.instance.objectNode();
    }

    public static ArrayNode array() {
        return JsonNodeFactory.instance.arrayNode();
    }

    public static JsonNode parseBytesAsJson(byte[] value) {
        if (value == null || value.length == 0) {
            return JsonNodeFactory.instance.objectNode();
        }
        try {
            // Attachment/user config travels as raw bytes in the Java API but as nested JSON in
            // the HTTP API. Parsing once here keeps both shapes lossless for structured payloads.
            return MAPPER.readTree(value);
        } catch (IOException e) {
            throw new IllegalArgumentException("invalid json bytes", e);
        }
    }

    public static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }

    public static long longValue(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? 0L : value.asLong();
    }

    public static boolean boolValue(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return !value.isMissingNode() && !value.isNull() && value.asBoolean();
    }

    public static byte[] bytesValue(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return new byte[0];
        }
        try {
            return value.binaryValue();
        } catch (IOException e) {
            throw new IllegalArgumentException("invalid base64 field " + field, e);
        }
    }

    public static JsonNode itemsNode(JsonNode node, String field) {
        if (node.isArray()) {
            return node;
        }
        return node.path(field);
    }

    public static UserRef userRef(JsonNode node) {
        return new UserRef(longValue(node, "node_id"), longValue(node, "user_id"));
    }

    public static User user(JsonNode node) {
        JsonNode profile = node.path("profile");
        if (profile.isMissingNode() || profile.isNull()) {
            profile = node.path("profile_json");
        }
        byte[] profileJson;
        if (profile.isMissingNode() || profile.isNull()) {
            profileJson = new byte[0];
        } else if (profile.isContainerNode()) {
            try {
                // Newer HTTP responses inline profile as an object/array, while older endpoints
                // may still expose profile_json as a base64 blob. The model keeps bytes so callers
                // do not have to care which wire shape the server picked.
                profileJson = MAPPER.writeValueAsBytes(profile);
            } catch (IOException e) {
                throw new IllegalArgumentException("invalid profile json", e);
            }
        } else {
            profileJson = bytesValue(node, "profile_json");
        }
        return new User(
            longValue(node, "node_id"),
            longValue(node, "user_id"),
            text(node, "username"),
            text(node, "role"),
            profileJson,
            boolValue(node, "system_reserved"),
            text(node, "created_at"),
            text(node, "updated_at"),
            longValue(node, "origin_node_id")
        );
    }

    public static Message message(JsonNode node) {
        String createdAt = text(node, "created_at_hlc");
        if (createdAt.isEmpty()) {
            // Some REST handlers still return wall-clock created_at while the realtime protocol
            // uses created_at_hlc. Falling back keeps cursor ordering separate from timestamp
            // presentation without forcing every caller to branch on server version.
            createdAt = text(node, "created_at");
        }
        return new Message(
            userRef(node.path("recipient")),
            longValue(node, "node_id"),
            longValue(node, "seq"),
            userRef(node.path("sender")),
            bytesValue(node, "body"),
            createdAt
        );
    }

    public static Attachment attachment(JsonNode node) {
        return new Attachment(
            userRef(node.path("owner")),
            userRef(node.path("subject")),
            // HTTP responses use kebab/underscore string tags whereas the Java model exposes an
            // enum. Normalizing here keeps attachment-specific logic out of higher layers.
            AttachmentType.valueOf(text(node, "attachment_type").toUpperCase().replace('-', '_')),
            bytesValue(node, "config_json"),
            text(node, "attached_at"),
            text(node, "deleted_at"),
            longValue(node, "origin_node_id")
        );
    }

    public static Subscription subscription(Attachment attachment) {
        return new Subscription(
            attachment.owner(),
            attachment.subject(),
            attachment.attachedAt(),
            attachment.deletedAt(),
            attachment.originNodeId()
        );
    }

    public static BlacklistEntry blacklistEntry(Attachment attachment) {
        return new BlacklistEntry(
            attachment.owner(),
            attachment.subject(),
            attachment.attachedAt(),
            attachment.deletedAt(),
            attachment.originNodeId()
        );
    }

    public static ClusterNode clusterNode(JsonNode node) {
        return new ClusterNode(
            longValue(node, "node_id"),
            boolValue(node, "is_local"),
            text(node, "configured_url"),
            text(node, "source")
        );
    }

    public static LoggedInUser loggedInUser(JsonNode node) {
        return new LoggedInUser(
            longValue(node, "node_id"),
            longValue(node, "user_id"),
            text(node, "username")
        );
    }

    public static List<Message> messages(JsonNode node) {
        JsonNode items = itemsNode(node, "items");
        List<Message> out = new ArrayList<>();
        for (JsonNode item : items) {
            out.add(message(item));
        }
        return out;
    }

    public static List<Attachment> attachments(JsonNode node) {
        JsonNode items = itemsNode(node, "items");
        List<Attachment> out = new ArrayList<>();
        for (JsonNode item : items) {
            out.add(attachment(item));
        }
        return out;
    }

    public static List<ClusterNode> clusterNodes(JsonNode node) {
        // Cluster endpoints are not perfectly uniform: some return a top-level array, others wrap
        // it in nodes/items. This adapter accepts every known shape so transport code stays dumb.
        JsonNode items = node.isArray() ? node : (node.has("nodes") ? node.path("nodes") : node.path("items"));
        List<ClusterNode> out = new ArrayList<>();
        for (JsonNode item : items) {
            out.add(clusterNode(item));
        }
        return out;
    }

    public static List<LoggedInUser> loggedInUsers(JsonNode node) {
        JsonNode items = itemsNode(node, "items");
        List<LoggedInUser> out = new ArrayList<>();
        for (JsonNode item : items) {
            out.add(loggedInUser(item));
        }
        return out;
    }
}
