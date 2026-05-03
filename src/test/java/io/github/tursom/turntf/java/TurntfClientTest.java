package io.github.tursom.turntf.java;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.mindrot.jbcrypt.BCrypt;
import notifier.client.v1.Client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TurntfClientTest {
    @Test
    void listUsersRpcSupportsFiltersAndVisibility() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
                @Override
                public void onMessage(WebSocket webSocket, okio.ByteString bytes) {
                    try {
                        Client.ClientEnvelope env = Client.ClientEnvelope.parseFrom(bytes.toByteArray());
                        switch (env.getBodyCase()) {
                            case LOGIN -> webSocket.send(okio.ByteString.of(Client.ServerEnvelope.newBuilder()
                                .setLoginResponse(Client.LoginResponse.newBuilder()
                                    .setUser(Client.User.newBuilder()
                                        .setNodeId(4096)
                                        .setUserId(1025)
                                        .setUsername("alice")
                                        .setRole("user")
                                        .setLoginName("alice.login")
                                        .build())
                                    .setProtocolVersion("client-v1alpha1")
                                    .setSessionRef(Client.SessionRef.newBuilder().setServingNodeId(4096).setSessionId("session-list-users").build())
                                    .build())
                                .build().toByteArray()));
                            case LIST_USERS -> {
                                if (env.getListUsers().getRequestId() == 1) {
                                    assertFalse(env.getListUsers().hasUid());
                                    assertEquals("carol", env.getListUsers().getName());
                                    webSocket.send(okio.ByteString.of(Client.ServerEnvelope.newBuilder()
                                        .setListUsersResponse(Client.ListUsersResponse.newBuilder()
                                            .setRequestId(env.getListUsers().getRequestId())
                                            .addItems(Client.User.newBuilder()
                                                .setNodeId(4096)
                                                .setUserId(1027)
                                                .setUsername("carol")
                                                .setRole("user")
                                                .build())
                                            .setCount(1)
                                            .build())
                                        .build().toByteArray()));
                                } else if (env.getListUsers().getRequestId() == 2) {
                                    assertTrue(env.getListUsers().hasUid());
                                    assertEquals(4096, env.getListUsers().getUid().getNodeId());
                                    assertEquals(1027, env.getListUsers().getUid().getUserId());
                                    assertEquals("carol", env.getListUsers().getName());
                                    webSocket.send(okio.ByteString.of(Client.ServerEnvelope.newBuilder()
                                        .setListUsersResponse(Client.ListUsersResponse.newBuilder()
                                            .setRequestId(env.getListUsers().getRequestId())
                                            .addItems(Client.User.newBuilder()
                                                .setNodeId(4096)
                                                .setUserId(1027)
                                                .setUsername("carol")
                                                .setRole("user")
                                                .build())
                                            .setCount(1)
                                            .build())
                                        .build().toByteArray()));
                                } else {
                                    assertFalse(env.getListUsers().hasUid());
                                    assertEquals("", env.getListUsers().getName());
                                    webSocket.send(okio.ByteString.of(Client.ServerEnvelope.newBuilder()
                                        .setListUsersResponse(Client.ListUsersResponse.newBuilder()
                                            .setRequestId(env.getListUsers().getRequestId())
                                            .addItems(Client.User.newBuilder()
                                                .setNodeId(4096)
                                                .setUserId(1025)
                                                .setUsername("alice")
                                                .setRole("user")
                                                .setLoginName("alice.login")
                                                .build())
                                            .addItems(Client.User.newBuilder()
                                                .setNodeId(4096)
                                                .setUserId(1027)
                                                .setUsername("carol")
                                                .setRole("user")
                                                .build())
                                            .setCount(2)
                                            .build())
                                        .build().toByteArray()));
                                    webSocket.close(1000, "done");
                                }
                            }
                            default -> {
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
            }));
            server.start();

            TurntfClient client = new TurntfClient(new Config(
                server.url("/").toString(),
                new Credentials(4096, 1025, PasswordInput.plain("alice-password"))
            ));

            client.connect().join();

            List<User> byName = client.listUsers("  carol  ").join();
            assertEquals(1, byName.size());
            assertEquals("carol", byName.get(0).username());
            assertEquals("", byName.get(0).loginName());

            List<User> combined = client.listUsers(new ListUsersFilter("carol", new UserRef(4096, 1027))).join();
            assertEquals(1, combined.size());
            assertEquals("carol", combined.get(0).username());

            List<User> all = client.listUsers().join();
            assertEquals(2, all.size());
            assertEquals("alice.login", all.get(0).loginName());
            assertEquals("", all.get(1).loginName());

            assertThrows(IllegalArgumentException.class, () -> client.listUsers(new UserRef(4096, 0)));
            client.close();
        }
    }

    @Test
    void loginAckSendMetadataAndPing() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            CountDownLatch acked = new CountDownLatch(1);
            server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
                private Client.UserMetadata metadata(String key, byte[] value, String updatedAt, String deletedAt, String expiresAt) {
                    Client.UserMetadata.Builder builder = Client.UserMetadata.newBuilder()
                        .setOwner(Client.UserRef.newBuilder().setNodeId(4096).setUserId(1025).build())
                        .setKey(key)
                        .setValue(com.google.protobuf.ByteString.copyFrom(value))
                        .setUpdatedAt(updatedAt)
                        .setOriginNodeId(4096);
                    if (deletedAt != null) {
                        builder.setDeletedAt(deletedAt);
                    }
                    if (expiresAt != null) {
                        builder.setExpiresAt(expiresAt);
                    }
                    return builder.build();
                }

                @Override
                public void onMessage(WebSocket webSocket, okio.ByteString bytes) {
                    try {
                        Client.ClientEnvelope env = Client.ClientEnvelope.parseFrom(bytes.toByteArray());
                        switch (env.getBodyCase()) {
                            case LOGIN -> {
                                assertTrue(BCrypt.checkpw("alice-password", env.getLogin().getPassword()));
                                webSocket.send(okio.ByteString.of(Client.ServerEnvelope.newBuilder()
                                    .setLoginResponse(Client.LoginResponse.newBuilder()
                                        .setUser(Client.User.newBuilder().setNodeId(4096).setUserId(1025).setUsername("alice").setRole("user").build())
                                        .setProtocolVersion("client-v1alpha1")
                                        .setSessionRef(Client.SessionRef.newBuilder().setServingNodeId(4096).setSessionId("session-a").build())
                                        .build())
                                    .build().toByteArray()));
                                webSocket.send(okio.ByteString.of(Client.ServerEnvelope.newBuilder()
                                    .setMessagePushed(Client.MessagePushed.newBuilder()
                                        .setMessage(Client.Message.newBuilder()
                                            .setRecipient(Client.UserRef.newBuilder().setNodeId(4096).setUserId(1025).build())
                                            .setNodeId(4096)
                                            .setSeq(7)
                                            .setSender(Client.UserRef.newBuilder().setNodeId(4096).setUserId(1).build())
                                            .setBody(com.google.protobuf.ByteString.copyFrom(new byte[]{1, 2}))
                                            .setCreatedAtHlc("hlc1")
                                            .build())
                                        .build())
                                    .build().toByteArray()));
                            }
                            case ACK_MESSAGE -> acked.countDown();
                            case GET_USER_METADATA -> webSocket.send(okio.ByteString.of(Client.ServerEnvelope.newBuilder()
                                .setGetUserMetadataResponse(Client.GetUserMetadataResponse.newBuilder()
                                    .setRequestId(env.getGetUserMetadata().getRequestId())
                                    .setMetadata(metadata("prefs.theme", new byte[]{1, 2, 3}, "hlc-meta-1", null, "2026-05-01T00:00:00Z"))
                                    .build())
                                .build().toByteArray()));
                            case UPSERT_USER_METADATA -> {
                                assertEquals("prefs.theme", env.getUpsertUserMetadata().getKey());
                                assertArrayEquals(new byte[]{9, 8, 7}, env.getUpsertUserMetadata().getValue().toByteArray());
                                assertTrue(env.getUpsertUserMetadata().hasExpiresAt());
                                assertEquals("2026-05-01T00:00:00Z", env.getUpsertUserMetadata().getExpiresAt().getValue());
                                webSocket.send(okio.ByteString.of(Client.ServerEnvelope.newBuilder()
                                    .setUpsertUserMetadataResponse(Client.UpsertUserMetadataResponse.newBuilder()
                                        .setRequestId(env.getUpsertUserMetadata().getRequestId())
                                        .setMetadata(metadata("prefs.theme", env.getUpsertUserMetadata().getValue().toByteArray(), "hlc-meta-2", null, env.getUpsertUserMetadata().getExpiresAt().getValue()))
                                        .build())
                                    .build().toByteArray()));
                            }
                            case DELETE_USER_METADATA -> webSocket.send(okio.ByteString.of(Client.ServerEnvelope.newBuilder()
                                .setDeleteUserMetadataResponse(Client.DeleteUserMetadataResponse.newBuilder()
                                    .setRequestId(env.getDeleteUserMetadata().getRequestId())
                                    .setMetadata(metadata("prefs.theme", new byte[]{9, 8, 7}, "hlc-meta-3", "hlc-meta-delete", "2026-05-01T00:00:00Z"))
                                    .build())
                                .build().toByteArray()));
                            case SCAN_USER_METADATA -> {
                                assertEquals("prefs.", env.getScanUserMetadata().getPrefix());
                                assertEquals("prefs.theme", env.getScanUserMetadata().getAfter());
                                assertEquals(2, env.getScanUserMetadata().getLimit());
                                webSocket.send(okio.ByteString.of(Client.ServerEnvelope.newBuilder()
                                    .setScanUserMetadataResponse(Client.ScanUserMetadataResponse.newBuilder()
                                        .setRequestId(env.getScanUserMetadata().getRequestId())
                                        .addItems(metadata("prefs.theme", new byte[]{1, 2, 3}, "hlc-meta-1", null, "2026-05-01T00:00:00Z"))
                                        .addItems(metadata("prefs.volume", new byte[]{4, 5}, "hlc-meta-4", null, null))
                                        .setCount(2)
                                        .setNextAfter("prefs.volume")
                                        .build())
                                    .build().toByteArray()));
                            }
                            case SEND_MESSAGE -> webSocket.send(okio.ByteString.of(Client.ServerEnvelope.newBuilder()
                                .setSendMessageResponse(Client.SendMessageResponse.newBuilder()
                                    .setRequestId(env.getSendMessage().getRequestId())
                                    .setMessage(Client.Message.newBuilder()
                                        .setRecipient(Client.UserRef.newBuilder().setNodeId(4096).setUserId(1025).build())
                                        .setNodeId(4096)
                                        .setSeq(8)
                                        .setSender(Client.UserRef.newBuilder().setNodeId(4096).setUserId(1025).build())
                                        .setBody(env.getSendMessage().getBody())
                                        .setCreatedAtHlc("hlc2")
                                        .build())
                                    .build())
                                .build().toByteArray()));
                            case PING -> {
                                webSocket.send(okio.ByteString.of(Client.ServerEnvelope.newBuilder()
                                    .setPong(Client.Pong.newBuilder().setRequestId(env.getPing().getRequestId()).build())
                                    .build().toByteArray()));
                                webSocket.close(1000, "done");
                            }
                            default -> {
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
            }));
            server.start();

            RecordingStore store = new RecordingStore();
            RecordingListener listener = new RecordingListener();
            TurntfClient client = new TurntfClient(new Config(
                server.url("/").toString(),
                new Credentials(4096, 1025, PasswordInput.plain("alice-password")),
                store,
                listener,
                null,
                true,
                java.time.Duration.ofSeconds(1),
                java.time.Duration.ofSeconds(30),
                java.time.Duration.ofHours(1),
                java.time.Duration.ofSeconds(3),
                true,
                false,
                false
            ));

            client.connect().join();
            assertTrue(acked.await(3, TimeUnit.SECONDS));

            UserRef owner = new UserRef(4096, 1025);
            UserMetadata metadata = client.getUserMetadata(owner, "prefs.theme").join();
            assertArrayEquals(new byte[]{1, 2, 3}, metadata.value());
            assertEquals("2026-05-01T00:00:00Z", metadata.expiresAt());

            UserMetadata upserted = client.upsertUserMetadata(owner, "prefs.theme", new byte[]{9, 8, 7}, "2026-05-01T00:00:00Z").join();
            assertEquals("hlc-meta-2", upserted.updatedAt());
            assertArrayEquals(new byte[]{9, 8, 7}, upserted.value());

            UserMetadata deleted = client.deleteUserMetadata(owner, "prefs.theme").join();
            assertEquals("hlc-meta-delete", deleted.deletedAt());

            UserMetadataScanResult scan = client.scanUserMetadata(owner, "prefs.", "prefs.theme", 2).join();
            assertEquals(2, scan.count());
            assertEquals("prefs.volume", scan.nextAfter());
            assertArrayEquals(new byte[]{4, 5}, scan.items().get(1).value());

            Message message = client.sendMessage(new SendMessageInput(new UserRef(4096, 1025), "payload".getBytes())).join();
            assertEquals(8, message.seq());
            client.ping().join();
            client.close();
            assertEquals(1, listener.logins.size());
            assertEquals(1, listener.messages.size());
        }
    }

    @Test
    void loginByLoginNameAndUserApisExposeLoginName() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
                @Override
                public void onMessage(WebSocket webSocket, okio.ByteString bytes) {
                    try {
                        Client.ClientEnvelope env = Client.ClientEnvelope.parseFrom(bytes.toByteArray());
                        switch (env.getBodyCase()) {
                            case LOGIN -> {
                                assertFalse(env.getLogin().hasUser());
                                assertEquals("alice.login", env.getLogin().getLoginName());
                                assertTrue(BCrypt.checkpw("alice-password", env.getLogin().getPassword()));
                                webSocket.send(okio.ByteString.of(Client.ServerEnvelope.newBuilder()
                                    .setLoginResponse(Client.LoginResponse.newBuilder()
                                        .setUser(Client.User.newBuilder()
                                            .setNodeId(4096)
                                            .setUserId(1025)
                                            .setUsername("alice")
                                            .setRole("user")
                                            .setLoginName("alice.login")
                                            .build())
                                        .setProtocolVersion("client-v1alpha1")
                                        .setSessionRef(Client.SessionRef.newBuilder().setServingNodeId(4096).setSessionId("session-login-name").build())
                                        .build())
                                    .build().toByteArray()));
                            }
                            case CREATE_USER -> {
                                assertEquals("bob.login", env.getCreateUser().getLoginName());
                                assertTrue(BCrypt.checkpw("bob-password", env.getCreateUser().getPassword()));
                                webSocket.send(okio.ByteString.of(Client.ServerEnvelope.newBuilder()
                                    .setCreateUserResponse(Client.CreateUserResponse.newBuilder()
                                        .setRequestId(env.getCreateUser().getRequestId())
                                        .setUser(Client.User.newBuilder()
                                            .setNodeId(4096)
                                            .setUserId(1026)
                                            .setUsername("bob")
                                            .setRole("user")
                                            .setLoginName("bob.login")
                                            .build())
                                        .build())
                                    .build().toByteArray()));
                            }
                            case UPDATE_USER -> {
                                assertTrue(env.getUpdateUser().hasLoginName());
                                assertEquals("", env.getUpdateUser().getLoginName().getValue());
                                webSocket.send(okio.ByteString.of(Client.ServerEnvelope.newBuilder()
                                    .setUpdateUserResponse(Client.UpdateUserResponse.newBuilder()
                                        .setRequestId(env.getUpdateUser().getRequestId())
                                        .setUser(Client.User.newBuilder()
                                            .setNodeId(4096)
                                            .setUserId(1026)
                                            .setUsername("bob")
                                            .setRole("user")
                                            .build())
                                        .build())
                                    .build().toByteArray()));
                            }
                            case LIST_NODE_LOGGED_IN_USERS -> {
                                webSocket.send(okio.ByteString.of(Client.ServerEnvelope.newBuilder()
                                    .setListNodeLoggedInUsersResponse(Client.ListNodeLoggedInUsersResponse.newBuilder()
                                        .setRequestId(env.getListNodeLoggedInUsers().getRequestId())
                                        .addItems(Client.LoggedInUser.newBuilder()
                                            .setNodeId(4096)
                                            .setUserId(1025)
                                            .setUsername("alice")
                                            .setLoginName("alice.login")
                                            .build())
                                        .build())
                                    .build().toByteArray()));
                                webSocket.close(1000, "done");
                            }
                            default -> {
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
            }));
            server.start();

            TurntfClient client = new TurntfClient(new Config(
                server.url("/").toString(),
                new Credentials("alice.login", PasswordInput.plain("alice-password"))
            ));

            client.connect().join();
            assertEquals("alice.login", client.currentLogin().orElseThrow().user().loginName());

            User created = client.createUser(new CreateUserRequest(
                "bob",
                "bob.login",
                PasswordInput.plain("bob-password"),
                "{\"tier\":\"gold\"}".getBytes(),
                "user"
            )).join();
            assertEquals("bob.login", created.loginName());

            User updated = client.updateUser(new UserRef(4096, 1026), new UpdateUserRequest(
                null,
                "",
                null,
                null,
                null
            )).join();
            assertEquals("", updated.loginName());

            List<LoggedInUser> loggedInUsers = client.listNodeLoggedInUsers(4096).join();
            assertEquals(1, loggedInUsers.size());
            assertEquals("alice.login", loggedInUsers.get(0).loginName());
            client.close();
        }
    }

    private static final class RecordingStore implements CursorStore {
        private final List<MessageCursor> cursors = new ArrayList<>();

        @Override
        public List<MessageCursor> loadSeenMessages() {
            return new ArrayList<>(cursors);
        }

        @Override
        public void saveMessage(Message message) {
            if (!cursors.contains(message.cursor())) {
                cursors.add(message.cursor());
            }
        }

        @Override
        public void saveCursor(MessageCursor cursor) {
            if (!cursors.contains(cursor)) {
                cursors.add(cursor);
            }
        }
    }

    private static final class RecordingListener extends NopClientListener {
        private final List<LoginInfo> logins = new ArrayList<>();
        private final List<Message> messages = new ArrayList<>();

        @Override
        public void onLogin(LoginInfo info) {
            logins.add(info);
        }

        @Override
        public void onMessage(Message message) {
            messages.add(message);
        }
    }
}
