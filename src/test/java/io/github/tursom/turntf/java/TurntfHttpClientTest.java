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
                        String password = body.path("password").asText();
                        assertTrue(BCrypt.checkpw("root", password));
                        return json(200, "{\"token\":\"admin-token\"}");
                    }
                    if ("/users".equals(path)) {
                        assertEquals("Bearer admin-token", request.getHeader("Authorization"));
                        JsonNode body = MAPPER.readTree(request.getBody().readUtf8());
                        assertTrue(BCrypt.checkpw("alice-password", body.path("password").asText()));
                        return json(201, """
                            {"node_id":4096,"user_id":1025,"username":"alice","role":"user","profile":{"tier":"gold"}}
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
                    return new MockResponse().setResponseCode(404);
                }
            });
            server.start();

            TurntfHttpClient client = new TurntfHttpClient(server.url("/").toString());
            String token = client.login(4096, 1, "root");
            assertEquals("admin-token", token);

            User user = client.createUser(token, new CreateUserRequest(
                "alice",
                PasswordInput.plain("alice-password"),
                "{\"tier\":\"gold\"}".getBytes(),
                "user"
            ));
            assertEquals(4096, user.nodeId());
            assertEquals(1025, user.userId());

            List<Message> items = client.listMessages(token, new UserRef(4096, 1025), 20);
            assertEquals(1, items.size());
            assertArrayEquals(new byte[]{(byte) 0xff, 0x00}, items.get(0).body());

            Message created = client.postMessage(token, new UserRef(4096, 1025), new byte[]{(byte) 0xff, 0x00});
            assertEquals(4, created.seq());
        }
    }

    private static MockResponse json(int status, String body) {
        return new MockResponse()
            .setResponseCode(status)
            .setHeader("Content-Type", "application/json")
            .setBody(body);
    }
}
