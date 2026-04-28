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
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurntfClientTest {
    @Test
    void loginAckSendAndPing() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            CountDownLatch acked = new CountDownLatch(1);
            server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
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

            Message message = client.sendMessage(new SendMessageInput(new UserRef(4096, 1025), "payload".getBytes())).join();
            assertEquals(8, message.seq());
            client.ping().join();
            client.close();
            assertEquals(1, listener.logins.size());
            assertEquals(1, listener.messages.size());
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
