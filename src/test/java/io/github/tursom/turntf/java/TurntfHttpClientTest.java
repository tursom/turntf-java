package io.github.tursom.turntf.java;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;
import org.mindrot.jbcrypt.BCrypt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TurntfHttpClientTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void requestsAndEncoding() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) {
                    try {
                        return handle(request);
                    } catch (Exception e) {
                        return new MockResponse().setResponseCode(500).setBody(e.getMessage());
                    }
                }

                private MockResponse handle(RecordedRequest request) throws Exception {
                    String path = request.getPath();
                    if ("/auth/login".equals(path)) {
                        JsonNode body = MAPPER.readTree(request.getBody().readUtf8());
                        if (body.has("login_name")) {
                            assertEquals("alice.login", body.path("login_name").asText());
                            assertTrue(body.path("node_id").isMissingNode());
                            assertTrue(body.path("user_id").isMissingNode());
                            assertTrue(BCrypt.checkpw("alice-password", body.path("password").asText()));
                            return json(200, "{\"token\":\"alice-token\"}");
                        }
                        assertEquals(4096, body.path("node_id").asLong());
                        assertEquals(1, body.path("user_id").asLong());
                        assertTrue(BCrypt.checkpw("root", body.path("password").asText()));
                        return json(200, "{\"token\":\"admin-token\"}");
                    }
                    if ("/users".equals(path)) {
                        assertEquals("Bearer admin-token", request.getHeader("Authorization"));
                        JsonNode body = MAPPER.readTree(request.getBody().readUtf8());
                        assertEquals("alice.login", body.path("login_name").asText());
                        assertTrue(BCrypt.checkpw("alice-password", body.path("password").asText()));
                        return json(201, """
                            {"node_id":4096,"user_id":1025,"username":"alice","login_name":"alice.login","role":"user","profile":{"tier":"gold"}}
                            """);
                    }
                    if ("/cluster/nodes/4096/logged-in-users".equals(path)) {
                        return json(200, """
                            {"items":[{"node_id":4096,"user_id":1025,"username":"alice","login_name":"alice.login"}]}
                            """);
                    }
                    if ("/nodes/4096/users/1025/messages?limit=20".equals(path)) {
                        return json(200, """
                            {"items":[{"recipient":{"node_id":4096,"user_id":1025},"node_id":4096,"seq":3,"sender":{"node_id":4096,"user_id":1},"body":"/wA=","created_at":"hlc1"}]}
                            """);
                    }
                    if ("/nodes/4096/users/1025/messages".equals(path) && "POST".equals(request.getMethod())) {
                        JsonNode body = MAPPER.readTree(request.getBody().readUtf8());
                        assertEquals("/wA=", body.path("body").asText());
                        return json(201, """
                            {"recipient":{"node_id":4096,"user_id":1025},"node_id":4096,"seq":4,"sender":{"node_id":4096,"user_id":1},"body":"/wA=","created_at":"hlc2"}
                            """);
                    }
                    if ("/nodes/4096/users/1025/metadata/prefs.theme".equals(path) && "GET".equals(request.getMethod())) {
                        return json(200, """
                            {"owner":{"node_id":4096,"user_id":1025},"key":"prefs.theme","value":"AQID","updated_at":"hlc-meta-1","expires_at":"2026-05-01T00:00:00Z","origin_node_id":4096}
                            """);
                    }
                    if ("/nodes/4096/users/1025/metadata/prefs.theme".equals(path) && "PUT".equals(request.getMethod())) {
                        JsonNode body = MAPPER.readTree(request.getBody().readUtf8());
                        assertEquals("AAEC", body.path("value").asText());
                        assertEquals("2026-05-01T00:00:00Z", body.path("expires_at").asText());
                        return json(201, """
                            {"owner":{"node_id":4096,"user_id":1025},"key":"prefs.theme","value":"AAEC","updated_at":"hlc-meta-2","expires_at":"2026-05-01T00:00:00Z","origin_node_id":4096}
                            """);
                    }
                    if ("/nodes/4096/users/1025/metadata/prefs.theme".equals(path) && "DELETE".equals(request.getMethod())) {
                        return json(200, """
                            {"owner":{"node_id":4096,"user_id":1025},"key":"prefs.theme","value":"AAEC","updated_at":"hlc-meta-3","deleted_at":"hlc-meta-delete","expires_at":"2026-05-01T00:00:00Z","origin_node_id":4096}
                            """);
                    }
                    if ("/nodes/4096/users/1025/metadata?prefix=prefs.&after=prefs.theme&limit=2".equals(path) && "GET".equals(request.getMethod())) {
                        return json(200, """
                            {"items":[
                              {"owner":{"node_id":4096,"user_id":1025},"key":"prefs.theme","value":"AQID","updated_at":"hlc-meta-1","expires_at":"2026-05-01T00:00:00Z","origin_node_id":4096},
                              {"owner":{"node_id":4096,"user_id":1025},"key":"prefs.volume","value":"BAU=","updated_at":"hlc-meta-4","origin_node_id":4096}
                            ],"count":2,"next_after":"prefs.volume"}
                            """);
                    }
                    return new MockResponse().setResponseCode(404);
                }
            });
            server.start();

            TurntfHttpClient client = new TurntfHttpClient(server.url("/").toString());
            String token = client.login(4096, 1, "root");
            assertEquals("admin-token", token);
            assertEquals("alice-token", client.login("alice.login", "alice-password"));

            User user = client.createUser(token, new CreateUserRequest(
                "alice",
                "alice.login",
                PasswordInput.plain("alice-password"),
                "{\"tier\":\"gold\"}".getBytes(),
                "user"
            ));
            assertEquals(4096, user.nodeId());
            assertEquals(1025, user.userId());
            assertEquals("alice.login", user.loginName());

            List<LoggedInUser> loggedInUsers = client.listNodeLoggedInUsers(token, 4096);
            assertEquals(1, loggedInUsers.size());
            assertEquals("alice.login", loggedInUsers.get(0).loginName());

            List<Message> items = client.listMessages(token, new UserRef(4096, 1025), 20);
            assertEquals(1, items.size());
            assertArrayEquals(new byte[]{(byte) 0xff, 0x00}, items.get(0).body());

            Message created = client.postMessage(token, new UserRef(4096, 1025), new byte[]{(byte) 0xff, 0x00});
            assertEquals(4, created.seq());

            UserMetadata metadata = client.getUserMetadata(token, new UserRef(4096, 1025), "prefs.theme");
            assertArrayEquals(new byte[]{1, 2, 3}, metadata.value());
            assertEquals("2026-05-01T00:00:00Z", metadata.expiresAt());

            UserMetadata upserted = client.upsertUserMetadata(token, new UserRef(4096, 1025), "prefs.theme", new byte[]{0, 1, 2}, "2026-05-01T00:00:00Z");
            assertArrayEquals(new byte[]{0, 1, 2}, upserted.value());
            assertEquals("hlc-meta-2", upserted.updatedAt());

            UserMetadata deleted = client.deleteUserMetadata(token, new UserRef(4096, 1025), "prefs.theme");
            assertEquals("hlc-meta-delete", deleted.deletedAt());

            UserMetadataScanResult scan = client.scanUserMetadata(token, new UserRef(4096, 1025), "prefs.", "prefs.theme", 2);
            assertEquals(2, scan.count());
            assertEquals("prefs.volume", scan.nextAfter());
            assertArrayEquals(new byte[]{4, 5}, scan.items().get(1).value());

            assertThrows(IllegalArgumentException.class, () -> client.getUserMetadata(token, new UserRef(4096, 1025), "bad key"));
            assertThrows(IllegalArgumentException.class, () -> client.scanUserMetadata(token, new UserRef(4096, 1025), "prefs.", "other.key", 2));
        }
    }

    private static MockResponse json(int status, String body) {
        return new MockResponse()
            .setResponseCode(status)
            .setHeader("Content-Type", "application/json")
            .setBody(body);
    }
}
