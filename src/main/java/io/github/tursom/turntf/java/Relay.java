package io.github.tursom.turntf.java;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

/**
 * 管理基于 {@link TurntfClient} 的 relay 连接，负责入站连接分发和出站连接创建。
 * <p>
 * 通过 {@link TurntfClient#relay()} 获取实例。入站连接通过 {@link #onConnection(Consumer)} 注册处理器接收，
 * 出站连接通过 {@link #connect(UserRef)} 或 {@link #connect(UserRef, RelayOptions)} 创建。
 */
public class Relay {
    private final TurntfClient client;
    private final ConcurrentMap<String, RelayConnection> connections = new ConcurrentHashMap<>();
    private volatile Consumer<RelayConnection> onConnection;

    /**
     * 创建 Relay 管理器（由 {@link TurntfClient#relay()} 调用）。
     *
     * @param client 关联的客户端
     */
    Relay(TurntfClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    /**
     * 返回关联的 TurntfClient。
     *
     * @return 客户端实例
     */
    public TurntfClient client() {
        return client;
    }

    /**
     * 注册入站 relay 连接的处理器。每个新入站连接会调用 handler。
     *
     * @param handler 入站连接处理器，接收新创建的 RelayConnection
     */
    public void onConnection(Consumer<RelayConnection> handler) {
        this.onConnection = handler;
    }

    /**
     * 使用默认选项向目标用户发起 relay 连接。
     *
     * @param target 目标用户
     * @return 连接成功后完成的 CompletableFuture
     */
    public CompletableFuture<RelayConnection> connect(UserRef target) {
        return connect(target, RelayOptions.defaults());
    }

    /**
     * 向目标用户发起 relay 连接。自动解析目标用户的在线会话并选择支持瞬时消息的会话。
     *
     * @param target  目标用户
     * @param options 连接配置，null 时使用默认配置
     * @return 连接成功后完成的 CompletableFuture
     */
    public CompletableFuture<RelayConnection> connect(UserRef target, RelayOptions options) {
        RelayOptions opts = options != null ? options : RelayOptions.defaults();

        // 获取当前登录信息
        return client.currentLogin()
            .map(loginInfo -> {
                // 1. 解析目标用户的在线会话
                return client.resolveUserSessions(target).thenCompose(sessions -> {
                    // 2. 选择支持瞬时消息的会话
                    ResolvedUserSessions.ResolvedSession targetSession = null;
                    for (ResolvedUserSessions.ResolvedSession s : sessions.sessions()) {
                        if (s.transientCapable()) {
                            targetSession = s;
                            break;
                        }
                    }
                    if (targetSession == null) {
                        return CompletableFuture.failedFuture(
                            new RelayError(RelayError.NOT_CONNECTED,
                                "no transient-capable session found for target user"));
                    }

                    String relayId = RelayConnection.newRelayId();
                    RelayConnection conn = new RelayConnection(
                        this, relayId, target,
                        targetSession.session(), loginInfo.sessionRef(), opts
                    );
                    conn.setOpening();

                    // 3. 注册连接
                    connections.put(relayId, conn);

                    // 4. 发送 OPEN 帧
                    RelayConnection.RelayEnvelope openEnv =
                        new RelayConnection.RelayEnvelope(
                            relayId, RelayConnection.RelayKind.OPEN,
                            loginInfo.sessionRef(), targetSession.session(),
                            0, 0, null, System.currentTimeMillis()
                        );

                    // 5. 发 OPEN + 等 ACK
                    CompletableFuture<RelayConnection> result = sendRelayEnvelope(conn, openEnv)
                        .thenRun(conn::startSendLoop)
                        .thenCompose(v -> conn.openFuture())
                        .orTimeout(opts.openTimeoutMs(), java.util.concurrent.TimeUnit.MILLISECONDS)
                        .thenApply(v -> conn);

                    // 清理回调（无论成功或失败）
                    result.whenComplete((v, err) -> {
                        if (err != null) {
                            connections.remove(relayId);
                            conn.handleClose(new RelayError(RelayError.OPEN_TIMEOUT,
                                "connection failed", err));
                        }
                    });

                    return result;
                });
            })
            .orElseGet(() -> CompletableFuture.failedFuture(
                new IllegalStateException("turntf client is not connected")));
    }

    /**
     * 检查 packet body 是否为 relay 帧，是则分发到对应连接。
     *
     * @param packet 收到的数据包
     * @return 如果是 relay 帧且已处理则返回 true，否则返回 false
     */
    public boolean handlePacket(Packet packet) {
        byte[] body = packet.body();
        if (body == null || body.length == 0) {
            return false;
        }

        RelayConnection.RelayEnvelope env;
        try {
            env = RelayConnection.RelayEnvelope.decode(body);
        } catch (Exception e) {
            // 不是 relay 帧
            return false;
        }

        String relayId = env.relayId();
        RelayConnection conn = connections.get(relayId);

        switch (env.kind()) {
            case OPEN -> {
                if (conn == null) {
                    // 入站 OPEN — 接受连接（传入 sender UserRef 作为 remotePeer）
                    acceptIncoming(env, packet.sender());
                }
                // 已存在相同 relayId 的连接，忽略
                return true;
            }

            case OPEN_ACK -> {
                if (conn != null && conn.state() == RelayState.OPENING) {
                    conn.handleOpenAck(env);
                }
                return true;
            }

            case CLOSE -> {
                if (conn != null) {
                    conn.handleClose(new RelayError(RelayError.REMOTE_CLOSE,
                        "remote peer closed connection"));
                }
                return true;
            }

            case ERROR -> {
                if (conn != null) {
                    String message = env.payload() != null ? new String(env.payload()) : "";
                    conn.handleClose(new RelayError(RelayError.PROTOCOL,
                        "remote peer error: " + message));
                }
                return true;
            }

            default -> {
                // DATA / ACK / PING / UNSPECIFIED
                if (conn != null) {
                    conn.handleEnvelope(env);
                }
                return true;
            }
        }
    }

    /**
     * 从管理器中移除连接。
     *
     * @param relayId 连接标识
     */
    void removeConnection(String relayId) {
        connections.remove(relayId);
    }

    // ---------- 私有方法 ----------

    /** 将入站 OPEN 帧转换为新的 RelayConnection 并通知用户处理器。 */
    private void acceptIncoming(RelayConnection.RelayEnvelope env, UserRef sender) {
        // remotePeer 从 OPEN 帧的 sender 信息推断（从 Packet.sender 获取）
        SessionRef remoteSession = env.senderSession();
        SessionRef mySession = env.targetSession();
        RelayOptions opts = RelayOptions.defaults();
        RelayConnection conn = new RelayConnection(
            this, env.relayId(), sender != null ? sender : new UserRef(0, 0),
            remoteSession, mySession, opts
        );
        conn.markOpen();

        // 处理并发 OPEN：同 relayId 的已有连接优先
        RelayConnection existing = connections.putIfAbsent(env.relayId(), conn);
        if (existing != null) {
            conn.handleClose(new RelayError(RelayError.DUPLICATE_OPEN,
                "concurrent OPEN, keeping existing connection"));
            return;
        }

        conn.startSendLoop();

        // 发送 OPEN_ACK（SenderSession = 本端，TargetSession = 对端）
        RelayConnection.RelayEnvelope openAckEnv =
            new RelayConnection.RelayEnvelope(
                env.relayId(), RelayConnection.RelayKind.OPEN_ACK,
                conn.mySession(), conn.remoteSession(),
                0, 0, null, System.currentTimeMillis()
            );
        sendRelayEnvelope(conn, openAckEnv);

        // 通知用户处理器
        Consumer<RelayConnection> handler = this.onConnection;
        if (handler != null) {
            try {
                handler.accept(conn);
            } catch (Exception ignored) {
                // 处理器异常不影响连接建立
            }
        }
    }

    /**
     * 通过 client 发送 relay 信封。使用 best_effort 模式，
     * 因为控制帧不需要 route_retry 语义。
     */
    private static CompletableFuture<Void> sendRelayEnvelope(
            RelayConnection conn, RelayConnection.RelayEnvelope env) {
        try {
            byte[] body = RelayConnection.RelayEnvelope.encode(env);
            return conn.relay().client().sendPacketToSession(
                conn.remotePeer(), conn.remoteSession(), body,
                DeliveryMode.BEST_EFFORT
            ).thenApply(ignored -> null);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }
}
