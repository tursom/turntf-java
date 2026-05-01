package io.github.tursom.turntf.java;

import io.github.tursom.turntf.java.internal.ProtoAdapters;
import io.github.tursom.turntf.java.internal.Validation;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import notifier.client.v1.Client;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Websocket-first high-level turntf client.
 *
 * <p>The client owns the full realtime protocol lifecycle: websocket dial, first-frame login,
 * reconnect with {@code seen_messages}, request/response correlation, ping, persistent message
 * durability, optional ack emission, and push delivery through {@link ClientListener}.
 */
public class TurntfClient {
    private static final String CLOSED_MESSAGE = "turntf client is closed";
    private static final String NOT_CONNECTED_MESSAGE = "turntf client is not connected";
    private static final String DISCONNECTED_MESSAGE = "turntf websocket disconnected";

    private final Config config;
    private final CursorStore cursorStore;
    private final ClientListener listener;
    private final OkHttpClient httpClient;
    private final TurntfHttpClient http;
    private final ScheduledExecutorService scheduler;
    // All in-flight RPCs, including ping, share the same request-id namespace as the protobuf
    // protocol. A reconnect blows away the whole map because response frames are scoped to a
    // single websocket session and cannot be safely replayed across sockets.
    private final ConcurrentMap<Long, CompletableFuture<Object>> pending = new ConcurrentHashMap<>();
    private final AtomicLong requestId = new AtomicLong();
    private final AtomicBoolean firstSignal = new AtomicBoolean();
    // stateLock protects lifecycle transitions that must update socket/auth/loginInfo together.
    // Individual reads stay volatile so hot paths such as sendEnvelope avoid coarse locking.
    private final Object stateLock = new Object();

    private volatile WebSocket webSocket;
    private volatile LoginInfo loginInfo;
    private volatile boolean authenticated;
    private volatile boolean closed;
    private volatile boolean stopReconnect;
    private volatile Thread managerThread;
    private volatile ScheduledFuture<?> pingTask;
    private volatile CompletableFuture<Void> firstConnect = new CompletableFuture<>();

    public TurntfClient(Config config) {
        this.config = normalize(config);
        this.cursorStore = this.config.cursorStore();
        this.listener = this.config.listener();
        this.httpClient = this.config.httpClient();
        this.http = new TurntfHttpClient(this.config.baseUrl(), this.httpClient);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("turntf-java-scheduler"));
    }

    public TurntfHttpClient http() {
        return http;
    }

    /**
     * Returns the current authenticated login snapshot, if the websocket is presently usable.
     */
    public Optional<LoginInfo> currentLogin() {
        return Optional.ofNullable(loginInfo);
    }

    /**
     * Delegates to {@link TurntfHttpClient#login(long, long, String)}.
     */
    public String login(long nodeId, long userId, String password) {
        return http.login(nodeId, userId, password);
    }

    /**
     * Delegates to {@link TurntfHttpClient#login(String, String)}.
     */
    public String login(String loginName, String password) {
        return http.login(loginName, password);
    }

    /**
     * Delegates to {@link TurntfHttpClient#loginWithPassword(long, long, PasswordInput)}.
     */
    public String loginWithPassword(long nodeId, long userId, PasswordInput password) {
        return http.loginWithPassword(nodeId, userId, password);
    }

    /**
     * Delegates to {@link TurntfHttpClient#loginWithPassword(String, PasswordInput)}.
     */
    public String loginWithPassword(String loginName, PasswordInput password) {
        return http.loginWithPassword(loginName, password);
    }

    /**
     * Starts the websocket lifecycle and resolves when the first authenticated session becomes
     * usable.
     *
     * <p>Later reconnects are handled internally by the manager thread and do not require callers
     * to invoke {@code connect()} again.
     */
    public CompletableFuture<Void> connect() {
        synchronized (stateLock) {
            if (closed) {
                return CompletableFuture.failedFuture(closedError());
            }
            if (authenticated) {
                return CompletableFuture.completedFuture(null);
            }
            if (managerThread == null || !managerThread.isAlive()) {
                if (firstConnect.isDone()) {
                    // Each explicit connect() attempt gets a fresh firstConnect future. After the
                    // first successful authentication the manager thread may reconnect internally,
                    // but callers waiting on connect() only care about the first usable session.
                    firstConnect = new CompletableFuture<>();
                    firstSignal.set(false);
                }
                managerThread = Thread.ofPlatform().name("turntf-java-client").start(this::runLoop);
            }
            return firstConnect;
        }
    }

    /**
     * Stops reconnect attempts, closes the current websocket, and fails all pending RPCs.
     */
    public void close() {
        synchronized (stateLock) {
            if (closed) {
                return;
            }
            closed = true;
            cancelPingTask();
            WebSocket socket = webSocket;
            webSocket = null;
            if (socket != null) {
                socket.close(1000, "client close");
            }
            if (managerThread != null) {
                managerThread.interrupt();
            }
        }
        failAllPending(closedError());
        scheduler.shutdownNow();
    }

    /**
     * Sends an application-level ping over the websocket RPC channel.
     */
    public CompletableFuture<Void> ping() {
        return rpc(
            requestId -> Client.ClientEnvelope.newBuilder()
                .setPing(Client.Ping.newBuilder().setRequestId(requestId).build())
                .build(),
            ignored -> null
        );
    }

    /**
     * Sends a durable message through the websocket API.
     *
     * <p>The returned future completes only after the echoed persistent message has been converted
     * back into the public model and persisted through {@link CursorStore}.
     */
    public CompletableFuture<Message> sendMessage(SendMessageInput input) {
        Validation.validateUserRef(input.target(), "target");
        if (input.body() == null || input.body().length == 0) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("body is required"));
        }
        return rpc(
            requestId -> Client.ClientEnvelope.newBuilder()
                .setSendMessage(Client.SendMessageRequest.newBuilder()
                    .setRequestId(requestId)
                    .setTarget(ProtoAdapters.toProto(input.target()))
                    .setBody(com.google.protobuf.ByteString.copyFrom(input.body()))
                    .setDeliveryKind(Client.ClientDeliveryKind.CLIENT_DELIVERY_KIND_PERSISTENT)
                    .setDeliveryMode(Client.ClientDeliveryMode.CLIENT_DELIVERY_MODE_UNSPECIFIED)
                    .setSyncMode(Client.ClientMessageSyncMode.CLIENT_MESSAGE_SYNC_MODE_UNSPECIFIED)
                    .build())
                .build(),
            value -> requireType(value, Message.class, "missing message in send_message_response")
        );
    }

    public CompletableFuture<Message> postMessage(SendMessageInput input) {
        return sendMessage(input);
    }

    /**
     * Sends a transient packet through the websocket API.
     *
     * <p>If {@code targetSession} is present, the server will attempt to route the packet to that
     * concrete online session instead of picking any current session for the target user.
     */
    public CompletableFuture<RelayAccepted> sendPacket(SendPacketInput input) {
        Validation.validateUserRef(input.target(), "target");
        if (input.body() == null || input.body().length == 0) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("body is required"));
        }
        Validation.validateDeliveryMode(input.deliveryMode());
        if (input.targetSession() != null && !input.targetSession().isZero()) {
            Validation.validateSessionRef(input.targetSession(), "targetSession");
        }
        return rpc(
            requestId -> {
                Client.SendMessageRequest.Builder builder = Client.SendMessageRequest.newBuilder()
                    .setRequestId(requestId)
                    .setTarget(ProtoAdapters.toProto(input.target()))
                    .setBody(com.google.protobuf.ByteString.copyFrom(input.body()))
                    .setDeliveryKind(Client.ClientDeliveryKind.CLIENT_DELIVERY_KIND_TRANSIENT)
                    .setDeliveryMode(ProtoAdapters.toProto(input.deliveryMode()))
                    .setSyncMode(Client.ClientMessageSyncMode.CLIENT_MESSAGE_SYNC_MODE_UNSPECIFIED);
                if (input.targetSession() != null && !input.targetSession().isZero()) {
                    builder.setTargetSession(ProtoAdapters.toProto(input.targetSession()));
                }
                return Client.ClientEnvelope.newBuilder().setSendMessage(builder.build()).build();
            },
            value -> requireType(value, RelayAccepted.class, "missing transient_accepted in send_message_response")
        );
    }

    public CompletableFuture<RelayAccepted> sendPacketToSession(UserRef target, SessionRef session, byte[] body, DeliveryMode mode) {
        return sendPacket(new SendPacketInput(target, body, mode, session));
    }

    public CompletableFuture<User> createUser(CreateUserRequest request) {
        if (request.username() == null || request.username().isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("username is required"));
        }
        if (request.role() == null || request.role().isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("role is required"));
        }
        return rpc(
            requestId -> {
                Client.CreateUserRequest.Builder builder = Client.CreateUserRequest.newBuilder()
                    .setRequestId(requestId)
                    .setUsername(request.username())
                    .setPassword(request.password() == null ? "" : request.password().wireValue())
                    .setProfileJson(com.google.protobuf.ByteString.copyFrom(request.profileJson() == null ? new byte[0] : request.profileJson()))
                    .setRole(request.role());
                if (request.loginName() != null) {
                    builder.setLoginName(request.loginName());
                }
                return Client.ClientEnvelope.newBuilder().setCreateUser(builder.build()).build();
            },
            value -> requireType(value, User.class, "missing user in create_user_response")
        );
    }

    public CompletableFuture<User> createChannel(CreateUserRequest request) {
        return createUser(new CreateUserRequest(
            request.username(),
            request.loginName(),
            request.password(),
            request.profileJson(),
            request.role() == null || request.role().isEmpty() ? "channel" : request.role()
        ));
    }

    public CompletableFuture<User> getUser(UserRef target) {
        Validation.validateUserRef(target, "target");
        return rpc(
            requestId -> Client.ClientEnvelope.newBuilder()
                .setGetUser(Client.GetUserRequest.newBuilder()
                    .setRequestId(requestId)
                    .setUser(ProtoAdapters.toProto(target))
                    .build())
                .build(),
            value -> requireType(value, User.class, "missing user in get_user_response")
        );
    }

    public CompletableFuture<User> updateUser(UserRef target, UpdateUserRequest request) {
        Validation.validateUserRef(target, "target");
        return rpc(
            requestId -> {
                Client.UpdateUserRequest.Builder builder = Client.UpdateUserRequest.newBuilder()
                    .setRequestId(requestId)
                    .setUser(ProtoAdapters.toProto(target));
                if (request.username() != null) {
                    builder.setUsername(Client.StringField.newBuilder().setValue(request.username()).build());
                }
                if (request.password() != null) {
                    builder.setPassword(Client.StringField.newBuilder().setValue(request.password().wireValue()).build());
                }
                if (request.profileJson() != null) {
                    builder.setProfileJson(Client.BytesField.newBuilder().setValue(com.google.protobuf.ByteString.copyFrom(request.profileJson())).build());
                }
                if (request.role() != null) {
                    builder.setRole(Client.StringField.newBuilder().setValue(request.role()).build());
                }
                if (request.loginName() != null) {
                    // UpdateUserRequest is patch-like: omitted login_name means "leave unchanged",
                    // while the empty string means "explicitly unbind the current login name".
                    builder.setLoginName(Client.StringField.newBuilder().setValue(request.loginName()).build());
                }
                return Client.ClientEnvelope.newBuilder().setUpdateUser(builder.build()).build();
            },
            value -> requireType(value, User.class, "missing user in update_user_response")
        );
    }

    public CompletableFuture<DeleteUserResult> deleteUser(UserRef target) {
        Validation.validateUserRef(target, "target");
        return rpc(
            requestId -> Client.ClientEnvelope.newBuilder()
                .setDeleteUser(Client.DeleteUserRequest.newBuilder()
                    .setRequestId(requestId)
                    .setUser(ProtoAdapters.toProto(target))
                    .build())
                .build(),
            value -> requireType(value, DeleteUserResult.class, "missing status in delete_user_response")
        );
    }

    public CompletableFuture<Attachment> upsertAttachment(UserRef owner, UserRef subject, AttachmentType attachmentType, byte[] configJson) {
        Validation.validateUserRef(owner, "owner");
        Validation.validateUserRef(subject, "subject");
        return rpc(
            requestId -> Client.ClientEnvelope.newBuilder()
                .setUpsertUserAttachment(Client.UpsertUserAttachmentRequest.newBuilder()
                    .setRequestId(requestId)
                    .setOwner(ProtoAdapters.toProto(owner))
                    .setSubject(ProtoAdapters.toProto(subject))
                    .setAttachmentType(ProtoAdapters.toProto(attachmentType))
                    .setConfigJson(com.google.protobuf.ByteString.copyFrom(configJson == null ? new byte[0] : configJson))
                    .build())
                .build(),
            value -> requireType(value, Attachment.class, "missing attachment in upsert_user_attachment_response")
        );
    }

    public CompletableFuture<Attachment> deleteAttachment(UserRef owner, UserRef subject, AttachmentType attachmentType) {
        Validation.validateUserRef(owner, "owner");
        Validation.validateUserRef(subject, "subject");
        return rpc(
            requestId -> Client.ClientEnvelope.newBuilder()
                .setDeleteUserAttachment(Client.DeleteUserAttachmentRequest.newBuilder()
                    .setRequestId(requestId)
                    .setOwner(ProtoAdapters.toProto(owner))
                    .setSubject(ProtoAdapters.toProto(subject))
                    .setAttachmentType(ProtoAdapters.toProto(attachmentType))
                    .build())
                .build(),
            value -> requireType(value, Attachment.class, "missing attachment in delete_user_attachment_response")
        );
    }

    @SuppressWarnings("unchecked")
    public CompletableFuture<List<Attachment>> listAttachments(UserRef owner, AttachmentType attachmentType) {
        Validation.validateUserRef(owner, "owner");
        return rpc(
            requestId -> Client.ClientEnvelope.newBuilder()
                .setListUserAttachments(Client.ListUserAttachmentsRequest.newBuilder()
                    .setRequestId(requestId)
                    .setOwner(ProtoAdapters.toProto(owner))
                    .setAttachmentType(ProtoAdapters.toProto(attachmentType))
                    .build())
                .build(),
            value -> (List<Attachment>) requireType(value, List.class, "missing items in list_user_attachments_response")
        );
    }

    public CompletableFuture<Subscription> subscribeChannel(UserRef subscriber, UserRef channel) {
        return upsertAttachment(subscriber, channel, AttachmentType.CHANNEL_SUBSCRIPTION, "{}".getBytes())
            .thenApply(attachment -> new Subscription(attachment.owner(), attachment.subject(), attachment.attachedAt(), attachment.deletedAt(), attachment.originNodeId()));
    }

    public CompletableFuture<Void> createSubscription(UserRef subscriber, UserRef channel) {
        return subscribeChannel(subscriber, channel).thenApply(ignored -> null);
    }

    public CompletableFuture<Subscription> unsubscribeChannel(UserRef subscriber, UserRef channel) {
        return deleteAttachment(subscriber, channel, AttachmentType.CHANNEL_SUBSCRIPTION)
            .thenApply(attachment -> new Subscription(attachment.owner(), attachment.subject(), attachment.attachedAt(), attachment.deletedAt(), attachment.originNodeId()));
    }

    public CompletableFuture<List<Subscription>> listSubscriptions(UserRef subscriber) {
        return listAttachments(subscriber, AttachmentType.CHANNEL_SUBSCRIPTION)
            .thenApply(items -> items.stream().map(item -> new Subscription(item.owner(), item.subject(), item.attachedAt(), item.deletedAt(), item.originNodeId())).toList());
    }

    public CompletableFuture<BlacklistEntry> blockUser(UserRef owner, UserRef blocked) {
        return upsertAttachment(owner, blocked, AttachmentType.USER_BLACKLIST, "{}".getBytes())
            .thenApply(attachment -> new BlacklistEntry(attachment.owner(), attachment.subject(), attachment.attachedAt(), attachment.deletedAt(), attachment.originNodeId()));
    }

    public CompletableFuture<BlacklistEntry> unblockUser(UserRef owner, UserRef blocked) {
        return deleteAttachment(owner, blocked, AttachmentType.USER_BLACKLIST)
            .thenApply(attachment -> new BlacklistEntry(attachment.owner(), attachment.subject(), attachment.attachedAt(), attachment.deletedAt(), attachment.originNodeId()));
    }

    public CompletableFuture<List<BlacklistEntry>> listBlockedUsers(UserRef owner) {
        return listAttachments(owner, AttachmentType.USER_BLACKLIST)
            .thenApply(items -> items.stream().map(item -> new BlacklistEntry(item.owner(), item.subject(), item.attachedAt(), item.deletedAt(), item.originNodeId())).toList());
    }

    /**
     * Reads a single private metadata entry for the given user through the websocket RPC channel.
     */
    public CompletableFuture<UserMetadata> getUserMetadata(UserRef owner, String key) {
        Validation.validateUserRef(owner, "owner");
        Validation.validateUserMetadataKey(key, "key");
        return rpc(
            requestId -> Client.ClientEnvelope.newBuilder()
                .setGetUserMetadata(Client.GetUserMetadataRequest.newBuilder()
                    .setRequestId(requestId)
                    .setOwner(ProtoAdapters.toProto(owner))
                    .setKey(key)
                    .build())
                .build(),
            value -> requireType(value, UserMetadata.class, "missing metadata in get_user_metadata_response")
        );
    }

    /**
     * Creates or replaces a private metadata entry for the given user.
     *
     * <p>The protobuf schema carries {@code expires_at} as an optional nested string field so the
     * client can distinguish between "server chooses default" and an explicit timestamp when the
     * protocol evolves. Today both HTTP and websocket paths expose that as a nullable string.
     */
    public CompletableFuture<UserMetadata> upsertUserMetadata(UserRef owner, String key, byte[] value, String expiresAt) {
        Validation.validateUserRef(owner, "owner");
        Validation.validateUserMetadataKey(key, "key");
        return rpc(
            requestId -> {
                Client.UpsertUserMetadataRequest.Builder builder = Client.UpsertUserMetadataRequest.newBuilder()
                    .setRequestId(requestId)
                    .setOwner(ProtoAdapters.toProto(owner))
                    .setKey(key)
                    .setValue(com.google.protobuf.ByteString.copyFrom(value == null ? new byte[0] : value));
                if (expiresAt != null) {
                    builder.setExpiresAt(Client.StringField.newBuilder().setValue(expiresAt).build());
                }
                return Client.ClientEnvelope.newBuilder().setUpsertUserMetadata(builder.build()).build();
            },
            valueObject -> requireType(valueObject, UserMetadata.class, "missing metadata in upsert_user_metadata_response")
        );
    }

    /**
     * Creates or replaces a private metadata entry with no expiration.
     */
    public CompletableFuture<UserMetadata> upsertUserMetadata(UserRef owner, String key, byte[] value) {
        return upsertUserMetadata(owner, key, value, null);
    }

    /**
     * Deletes a private metadata entry and returns the tombstone echoed by the server.
     */
    public CompletableFuture<UserMetadata> deleteUserMetadata(UserRef owner, String key) {
        Validation.validateUserRef(owner, "owner");
        Validation.validateUserMetadataKey(key, "key");
        return rpc(
            requestId -> Client.ClientEnvelope.newBuilder()
                .setDeleteUserMetadata(Client.DeleteUserMetadataRequest.newBuilder()
                    .setRequestId(requestId)
                    .setOwner(ProtoAdapters.toProto(owner))
                    .setKey(key)
                    .build())
                .build(),
            value -> requireType(value, UserMetadata.class, "missing metadata in delete_user_metadata_response")
        );
    }

    /**
     * Scans private metadata keys in ascending order.
     *
     * <p>{@code limit == 0} keeps the server-side default page size.
     */
    public CompletableFuture<UserMetadataScanResult> scanUserMetadata(UserRef owner, String prefix, String after, int limit) {
        Validation.validateUserRef(owner, "owner");
        Validation.validateUserMetadataScan(prefix, after, limit);
        return rpc(
            requestId -> Client.ClientEnvelope.newBuilder()
                .setScanUserMetadata(Client.ScanUserMetadataRequest.newBuilder()
                    .setRequestId(requestId)
                    .setOwner(ProtoAdapters.toProto(owner))
                    .setPrefix(prefix == null ? "" : prefix)
                    .setAfter(after == null ? "" : after)
                    .setLimit(limit)
                    .build())
                .build(),
            value -> requireType(value, UserMetadataScanResult.class, "missing scan result in scan_user_metadata_response")
        );
    }

    @SuppressWarnings("unchecked")
    public CompletableFuture<List<Message>> listMessages(UserRef target, int limit) {
        Validation.validateUserRef(target, "target");
        return rpc(
            requestId -> Client.ClientEnvelope.newBuilder()
                .setListMessages(Client.ListMessagesRequest.newBuilder()
                    .setRequestId(requestId)
                    .setUser(ProtoAdapters.toProto(target))
                    .setLimit(limit)
                    .build())
                .build(),
            value -> (List<Message>) requireType(value, List.class, "missing items in list_messages_response")
        );
    }

    @SuppressWarnings("unchecked")
    public CompletableFuture<List<Event>> listEvents(long after, int limit) {
        return rpc(
            requestId -> Client.ClientEnvelope.newBuilder()
                .setListEvents(Client.ListEventsRequest.newBuilder()
                    .setRequestId(requestId)
                    .setAfter(after)
                    .setLimit(limit)
                    .build())
                .build(),
            value -> (List<Event>) requireType(value, List.class, "missing items in list_events_response")
        );
    }

    @SuppressWarnings("unchecked")
    public CompletableFuture<List<ClusterNode>> listClusterNodes() {
        return rpc(
            requestId -> Client.ClientEnvelope.newBuilder()
                .setListClusterNodes(Client.ListClusterNodesRequest.newBuilder().setRequestId(requestId).build())
                .build(),
            value -> (List<ClusterNode>) requireType(value, List.class, "missing items in list_cluster_nodes_response")
        );
    }

    @SuppressWarnings("unchecked")
    public CompletableFuture<List<LoggedInUser>> listNodeLoggedInUsers(long nodeId) {
        Validation.requirePositive(nodeId, "nodeId");
        return rpc(
            requestId -> Client.ClientEnvelope.newBuilder()
                .setListNodeLoggedInUsers(Client.ListNodeLoggedInUsersRequest.newBuilder()
                    .setRequestId(requestId)
                    .setNodeId(nodeId)
                    .build())
                .build(),
            value -> (List<LoggedInUser>) requireType(value, List.class, "missing items in list_node_logged_in_users_response")
        );
    }

    public CompletableFuture<ResolvedUserSessions> resolveUserSessions(UserRef user) {
        Validation.validateUserRef(user, "user");
        return rpc(
            requestId -> Client.ClientEnvelope.newBuilder()
                .setResolveUserSessions(Client.ResolveUserSessionsRequest.newBuilder()
                    .setRequestId(requestId)
                    .setUser(ProtoAdapters.toProto(user))
                    .build())
                .build(),
            value -> requireType(value, ResolvedUserSessions.class, "missing sessions in resolve_user_sessions_response")
        );
    }

    public CompletableFuture<OperationsStatus> operationsStatus() {
        return rpc(
            requestId -> Client.ClientEnvelope.newBuilder()
                .setOperationsStatus(Client.OperationsStatusRequest.newBuilder().setRequestId(requestId).build())
                .build(),
            value -> requireType(value, OperationsStatus.class, "missing status in operations_status_response")
        );
    }

    public CompletableFuture<String> metrics() {
        return rpc(
            requestId -> Client.ClientEnvelope.newBuilder()
                .setMetrics(Client.MetricsRequest.newBuilder().setRequestId(requestId).build())
                .build(),
            value -> requireType(value, String.class, "missing text in metrics_response")
        );
    }

    private void runLoop() {
        Duration delay = config.initialReconnectDelay();
        while (!closed) {
            Attempt attempt = new Attempt();
            Throwable err = null;
            try {
                connectAttempt(attempt);
                delay = config.initialReconnectDelay();
                schedulePing();
                err = attempt.closeFuture.join();
                if (err == null) {
                    err = disconnectedError();
                }
            } catch (CompletionException e) {
                err = unwrap(e);
            } catch (Throwable t) {
                err = unwrap(t);
            } finally {
                cancelPingTask();
                synchronized (stateLock) {
                    if (webSocket == attempt.socket) {
                        webSocket = null;
                    }
                    authenticated = false;
                    loginInfo = null;
                }
                // Pending RPCs are tied to the websocket that carried their request. Completing
                // them with a disconnect here prevents callers from waiting until timeout on
                // responses that can never arrive from a future session.
                failAllPending(disconnectedError());
                if (err != null) {
                    listener.onDisconnect(err);
                }
            }

            if (closed) {
                signalFirstConnectFailureIfNeeded(closedError());
                return;
            }
            if (!shouldRetry(err)) {
                signalFirstConnectFailureIfNeeded(err);
                failAllPending(err);
                return;
            }
            listener.onError(err);
            // Exponential backoff is applied only after a fully failed attempt; a successful login
            // resets the delay so a later transient blip does not inherit a stale long sleep.
            sleep(delay);
            delay = delay.multipliedBy(2);
            if (delay.compareTo(config.maxReconnectDelay()) > 0) {
                delay = config.maxReconnectDelay();
            }
        }
    }

    private void connectAttempt(Attempt attempt) {
        // Seen cursors are snapshotted before dialing so the login frame represents a stable
        // replay watermark even if new messages arrive while the socket is coming up.
        List<MessageCursor> seen = new ArrayList<>(cursorStore.loadSeenMessages());
        Request request = new Request.Builder()
            .url(Validation.websocketUrl(config.baseUrl(), config.realtimeStream()))
            .build();
        attempt.seen = seen;
        attempt.socket = httpClient.newWebSocket(request, new AttemptListener(attempt));
        attempt.loginFuture.join();
    }

    private void schedulePing() {
        cancelPingTask();
        pingTask = scheduler.scheduleAtFixedRate(() -> ping().whenComplete((ignored, err) -> {
            Throwable actual = unwrap(err);
            if (actual != null && !isClosedLike(actual) && !isDisconnectedLike(actual)) {
                listener.onError(actual);
            }
        }), config.pingInterval().toMillis(), config.pingInterval().toMillis(), TimeUnit.MILLISECONDS);
    }

    private void cancelPingTask() {
        ScheduledFuture<?> task = pingTask;
        pingTask = null;
        if (task != null) {
            task.cancel(false);
        }
    }

    private boolean shouldRetry(Throwable err) {
        if (closed || stopReconnect || !config.reconnect()) {
            return false;
        }
        // Unauthorized is terminal for the current credentials; retrying would just hammer the
        // server with the same login failure until the process is restarted or reconfigured.
        return !(err instanceof ServerError serverError && serverError.unauthorized()) && !isClosedLike(err);
    }

    private <T> CompletableFuture<T> rpc(Function<Long, Client.ClientEnvelope> build, Function<Object, T> mapper) {
        long id = nextRequestId();
        CompletableFuture<Object> raw = new CompletableFuture<>();
        ScheduledFuture<?> timeout = scheduler.schedule(() -> {
            CompletableFuture<Object> removed = pending.remove(id);
            if (removed != null) {
                removed.completeExceptionally(new TimeoutException("request timed out"));
            }
        }, config.requestTimeout().toMillis(), TimeUnit.MILLISECONDS);

        raw.whenComplete((ignored, err) -> timeout.cancel(false));
        // Register before sending so a very fast server response cannot win the race and be
        // dropped as "unknown request". If sendEnvelope fails, we remove the future immediately.
        pending.put(id, raw);
        try {
            sendEnvelope(build.apply(id));
        } catch (Throwable error) {
            pending.remove(id);
            raw.completeExceptionally(error);
        }
        return raw.thenApply(mapper);
    }

    private void sendEnvelope(Client.ClientEnvelope envelope) {
        WebSocket socket = webSocket;
        if (closed) {
            throw closedError();
        }
        if (!authenticated || socket == null) {
            throw notConnectedError();
        }
        byte[] payload = envelope.toByteArray();
        if (!socket.send(okio.ByteString.of(payload, 0, payload.length))) {
            throw notConnectedError();
        }
    }

    private void completePending(long requestId, Object value) {
        CompletableFuture<Object> future = pending.remove(requestId);
        if (future != null) {
            future.complete(value);
        }
    }

    private void failPending(long requestId, Throwable error) {
        CompletableFuture<Object> future = pending.remove(requestId);
        if (future != null) {
            future.completeExceptionally(error);
        }
    }

    private void failAllPending(Throwable error) {
        // ConcurrentHashMap supports weakly consistent iteration, which is enough here: each
        // future is idempotently completed exceptionally, then the map is discarded as a batch.
        pending.forEach((id, future) -> future.completeExceptionally(error));
        pending.clear();
    }

    private long nextRequestId() {
        while (true) {
            long next = requestId.incrementAndGet();
            if (next > 0) {
                return next;
            }
            // The wire protocol expects a positive unsigned-ish request_id. If AtomicLong wraps,
            // reset and keep searching instead of leaking negative ids into the session.
            requestId.compareAndSet(next, 0);
        }
    }

    private void persistMessage(Message message) {
        // saveMessage first, saveCursor second: once the cursor is durable the next reconnect will
        // advertise it as seen, so the body must already be recoverable locally.
        cursorStore.saveMessage(message);
        cursorStore.saveCursor(message.cursor());
    }

    private void signalFirstConnectSuccess() {
        if (firstSignal.compareAndSet(false, true)) {
            firstConnect.complete(null);
        }
    }

    private void signalFirstConnectFailureIfNeeded(Throwable error) {
        if (firstSignal.compareAndSet(false, true)) {
            firstConnect.completeExceptionally(error);
        }
    }

    private void sleep(Duration delay) {
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static Config normalize(Config config) {
        if (config == null) {
            throw new IllegalArgumentException("config is required");
        }
        Validation.validateBaseUrl(config.baseUrl());
        if (config.credentials() == null) {
            throw new IllegalArgumentException("credentials are required");
        }
        config.credentials().password().validate();
        return new Config(
            config.baseUrl(),
            config.credentials(),
            config.cursorStore() == null ? new MemoryCursorStore() : config.cursorStore(),
            config.listener() == null ? new NopClientListener() : config.listener(),
            config.httpClient() == null ? new OkHttpClient() : config.httpClient(),
            config.reconnect(),
            safeDuration(config.initialReconnectDelay(), Duration.ofSeconds(1)),
            safeDuration(config.maxReconnectDelay(), Duration.ofSeconds(30)),
            safeDuration(config.pingInterval(), Duration.ofSeconds(30)),
            safeDuration(config.requestTimeout(), Duration.ofSeconds(10)),
            config.ackMessages(),
            config.transientOnly(),
            config.realtimeStream()
        );
    }

    private static Duration safeDuration(Duration input, Duration fallback) {
        return input == null || input.isZero() || input.isNegative() ? fallback : input;
    }

    private static <T> T requireType(Object value, Class<T> type, String message) {
        if (!type.isInstance(value)) {
            throw new ProtocolError(message);
        }
        return type.cast(value);
    }

    private static IllegalStateException closedError() {
        return new IllegalStateException(CLOSED_MESSAGE);
    }

    private static IllegalStateException notConnectedError() {
        return new IllegalStateException(NOT_CONNECTED_MESSAGE);
    }

    private static IllegalStateException disconnectedError() {
        return new IllegalStateException(DISCONNECTED_MESSAGE);
    }

    private static boolean isClosedLike(Throwable error) {
        return error instanceof IllegalStateException state && CLOSED_MESSAGE.equals(state.getMessage());
    }

    private static boolean isDisconnectedLike(Throwable error) {
        if (error instanceof IllegalStateException state) {
            return NOT_CONNECTED_MESSAGE.equals(state.getMessage()) || DISCONNECTED_MESSAGE.equals(state.getMessage());
        }
        return false;
    }

    private static Throwable unwrap(Throwable error) {
        if (error == null) {
            return null;
        }
        if (error instanceof CompletionException completion && completion.getCause() != null) {
            return unwrap(completion.getCause());
        }
        return error;
    }

    private final class AttemptListener extends WebSocketListener {
        private final Attempt attempt;

        private AttemptListener(Attempt attempt) {
            this.attempt = attempt;
        }

        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            Client.ClientEnvelope.Builder envelope = Client.ClientEnvelope.newBuilder();
            Client.LoginRequest.Builder login = Client.LoginRequest.newBuilder()
                .setPassword(config.credentials().password().wireValue())
                .setTransientOnly(config.transientOnly());
            if (config.credentials().hasUserSelector()) {
                login.setUser(ProtoAdapters.toProto(config.credentials().user()));
            } else {
                login.setLoginName(config.credentials().loginName());
            }
            for (MessageCursor cursor : attempt.seen) {
                // Re-advertise the locally persisted replay watermark so the server can resume
                // after the latest durable cursor instead of replaying the whole mailbox.
                login.addSeenMessages(ProtoAdapters.toProto(cursor));
            }
            envelope.setLogin(login.build());
            byte[] payload = envelope.build().toByteArray();
            if (!webSocket.send(okio.ByteString.of(payload, 0, payload.length))) {
                attempt.loginFuture.completeExceptionally(notConnectedError());
                attempt.closeFuture.complete(notConnectedError());
            }
        }

        @Override
        public void onMessage(WebSocket webSocket, okio.ByteString bytes) {
            Client.ServerEnvelope env;
            try {
                env = Client.ServerEnvelope.parseFrom(bytes.toByteArray());
            } catch (Exception e) {
                listener.onError(new ProtocolError("invalid protobuf frame"));
                return;
            }
            if (!attempt.loginFuture.isDone()) {
                // The first successful server frame must complete the login handshake; only after
                // that point is it safe to route request/response traffic through pending RPCs.
                handleLoginEnvelope(webSocket, env);
                return;
            }
            handleAuthedEnvelope(env);
        }

        @Override
        public void onClosing(WebSocket webSocket, int code, String reason) {
            webSocket.close(code, reason);
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            attempt.closeFuture.complete(disconnectedError());
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            Throwable error = response == null ? new ConnectionError("dial", t) : new ConnectionError("dial", new RuntimeException("status=" + response.code(), t));
            attempt.loginFuture.completeExceptionally(error);
            attempt.closeFuture.complete(error);
        }

        private void handleLoginEnvelope(WebSocket webSocket, Client.ServerEnvelope env) {
            switch (env.getBodyCase()) {
                case LOGIN_RESPONSE -> {
                    LoginInfo info = ProtoAdapters.loginInfo(env.getLoginResponse());
                    synchronized (stateLock) {
                        TurntfClient.this.webSocket = webSocket;
                        TurntfClient.this.authenticated = true;
                        TurntfClient.this.loginInfo = info;
                    }
                    // Publish authenticated state before completing loginFuture so any caller that
                    // resumes from connect() can send RPCs immediately on the winning socket.
                    attempt.loginFuture.complete(info);
                    signalFirstConnectSuccess();
                    listener.onLogin(info);
                }
                case ERROR -> {
                    ServerError error = new ServerError(env.getError().getCode(), env.getError().getMessage(), env.getError().getRequestId());
                    if (error.unauthorized()) {
                        stopReconnect = true;
                    }
                    attempt.loginFuture.completeExceptionally(error);
                    attempt.closeFuture.complete(error);
                    webSocket.close(1008, "login failed");
                }
                default -> {
                    ProtocolError error = new ProtocolError("expected login_response or error");
                    attempt.loginFuture.completeExceptionally(error);
                    attempt.closeFuture.complete(error);
                    webSocket.close(1002, "protocol");
                }
            }
        }

        private void handleAuthedEnvelope(Client.ServerEnvelope env) {
            try {
                switch (env.getBodyCase()) {
                    case MESSAGE_PUSHED -> {
                        Message message = ProtoAdapters.fromProto(env.getMessagePushed().getMessage());
                        persistMessage(message);
                        if (config.ackMessages()) {
                            try {
                                // Ack only after local persistence. Otherwise a crash between ack
                                // and saveCursor would tell the server not to replay a message the
                                // client no longer has.
                                sendEnvelope(Client.ClientEnvelope.newBuilder()
                                    .setAckMessage(Client.AckMessage.newBuilder().setCursor(ProtoAdapters.toProto(message.cursor())).build())
                                    .build());
                            } catch (Throwable error) {
                                Throwable actual = unwrap(error);
                                if (actual != null && !isDisconnectedLike(actual) && !isClosedLike(actual)) {
                                    listener.onError(actual);
                                }
                            }
                        }
                        listener.onMessage(message);
                    }
                    case PACKET_PUSHED -> listener.onPacket(ProtoAdapters.fromProto(env.getPacketPushed().getPacket()));
                    case SEND_MESSAGE_RESPONSE -> {
                        long requestId = Validation.requireUnsigned(env.getSendMessageResponse().getRequestId(), "request_id");
                        switch (env.getSendMessageResponse().getBodyCase()) {
                            case MESSAGE -> {
                                Message message = ProtoAdapters.fromProto(env.getSendMessageResponse().getMessage());
                                // Persistent sends echo back the stored message, so record it with
                                // the same durability guarantees as push delivery. This keeps the
                                // local cursor store coherent for reconnect suppression.
                                persistMessage(message);
                                completePending(requestId, message);
                            }
                            case TRANSIENT_ACCEPTED -> completePending(requestId, ProtoAdapters.fromProto(env.getSendMessageResponse().getTransientAccepted()));
                            default -> failPending(requestId, new ProtocolError("empty send_message_response"));
                        }
                    }
                    case PONG -> completePending(Validation.requireUnsigned(env.getPong().getRequestId(), "request_id"), Boolean.TRUE);
                    case CREATE_USER_RESPONSE -> completePending(Validation.requireUnsigned(env.getCreateUserResponse().getRequestId(), "request_id"), ProtoAdapters.fromProto(env.getCreateUserResponse().getUser()));
                    case GET_USER_RESPONSE -> completePending(Validation.requireUnsigned(env.getGetUserResponse().getRequestId(), "request_id"), ProtoAdapters.fromProto(env.getGetUserResponse().getUser()));
                    case UPDATE_USER_RESPONSE -> completePending(Validation.requireUnsigned(env.getUpdateUserResponse().getRequestId(), "request_id"), ProtoAdapters.fromProto(env.getUpdateUserResponse().getUser()));
                    case DELETE_USER_RESPONSE -> completePending(Validation.requireUnsigned(env.getDeleteUserResponse().getRequestId(), "request_id"), ProtoAdapters.deleteUserResult(env.getDeleteUserResponse()));
                    case GET_USER_METADATA_RESPONSE -> completePending(Validation.requireUnsigned(env.getGetUserMetadataResponse().getRequestId(), "request_id"), ProtoAdapters.fromProto(env.getGetUserMetadataResponse().getMetadata()));
                    case UPSERT_USER_METADATA_RESPONSE -> completePending(Validation.requireUnsigned(env.getUpsertUserMetadataResponse().getRequestId(), "request_id"), ProtoAdapters.fromProto(env.getUpsertUserMetadataResponse().getMetadata()));
                    case DELETE_USER_METADATA_RESPONSE -> completePending(Validation.requireUnsigned(env.getDeleteUserMetadataResponse().getRequestId(), "request_id"), ProtoAdapters.fromProto(env.getDeleteUserMetadataResponse().getMetadata()));
                    case SCAN_USER_METADATA_RESPONSE -> completePending(Validation.requireUnsigned(env.getScanUserMetadataResponse().getRequestId(), "request_id"), ProtoAdapters.fromProto(env.getScanUserMetadataResponse()));
                    case LIST_MESSAGES_RESPONSE -> completePending(Validation.requireUnsigned(env.getListMessagesResponse().getRequestId(), "request_id"), ProtoAdapters.messages(env.getListMessagesResponse().getItemsList()));
                    case UPSERT_USER_ATTACHMENT_RESPONSE -> completePending(Validation.requireUnsigned(env.getUpsertUserAttachmentResponse().getRequestId(), "request_id"), ProtoAdapters.fromProto(env.getUpsertUserAttachmentResponse().getAttachment()));
                    case DELETE_USER_ATTACHMENT_RESPONSE -> completePending(Validation.requireUnsigned(env.getDeleteUserAttachmentResponse().getRequestId(), "request_id"), ProtoAdapters.fromProto(env.getDeleteUserAttachmentResponse().getAttachment()));
                    case LIST_USER_ATTACHMENTS_RESPONSE -> completePending(Validation.requireUnsigned(env.getListUserAttachmentsResponse().getRequestId(), "request_id"), ProtoAdapters.attachments(env.getListUserAttachmentsResponse().getItemsList()));
                    case LIST_EVENTS_RESPONSE -> completePending(Validation.requireUnsigned(env.getListEventsResponse().getRequestId(), "request_id"), ProtoAdapters.events(env.getListEventsResponse().getItemsList()));
                    case LIST_CLUSTER_NODES_RESPONSE -> completePending(Validation.requireUnsigned(env.getListClusterNodesResponse().getRequestId(), "request_id"), ProtoAdapters.clusterNodes(env.getListClusterNodesResponse().getItemsList()));
                    case LIST_NODE_LOGGED_IN_USERS_RESPONSE -> completePending(Validation.requireUnsigned(env.getListNodeLoggedInUsersResponse().getRequestId(), "request_id"), ProtoAdapters.loggedInUsers(env.getListNodeLoggedInUsersResponse().getItemsList()));
                    case RESOLVE_USER_SESSIONS_RESPONSE -> completePending(Validation.requireUnsigned(env.getResolveUserSessionsResponse().getRequestId(), "request_id"), ProtoAdapters.fromProto(env.getResolveUserSessionsResponse()));
                    case OPERATIONS_STATUS_RESPONSE -> completePending(Validation.requireUnsigned(env.getOperationsStatusResponse().getRequestId(), "request_id"), ProtoAdapters.fromProto(env.getOperationsStatusResponse().getStatus()));
                    case METRICS_RESPONSE -> completePending(Validation.requireUnsigned(env.getMetricsResponse().getRequestId(), "request_id"), env.getMetricsResponse().getText());
                    case ERROR -> {
                        ServerError error = new ServerError(env.getError().getCode(), env.getError().getMessage(), env.getError().getRequestId());
                        if (env.getError().getRequestId() != 0) {
                            failPending(Validation.requireUnsigned(env.getError().getRequestId(), "request_id"), error);
                        } else {
                            listener.onError(error);
                        }
                    }
                    case LOGIN_RESPONSE -> listener.onError(new ProtocolError("unexpected login_response after authentication"));
                    case BODY_NOT_SET -> listener.onError(new ProtocolError("unsupported server envelope"));
                }
            } catch (Throwable error) {
                listener.onError(unwrap(error));
            }
        }
    }

    private static final class Attempt {
        private final CompletableFuture<LoginInfo> loginFuture = new CompletableFuture<>();
        private final CompletableFuture<Throwable> closeFuture = new CompletableFuture<>();
        private List<MessageCursor> seen = List.of();
        private WebSocket socket;
    }

    private static final class NamedThreadFactory implements ThreadFactory {
        private final String name;

        private NamedThreadFactory(String name) {
            this.name = name;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = Executors.defaultThreadFactory().newThread(r);
            thread.setName(name);
            thread.setDaemon(true);
            return thread;
        }
    }
}
