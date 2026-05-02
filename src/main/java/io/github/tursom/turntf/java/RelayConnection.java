package io.github.tursom.turntf.java;

import com.google.protobuf.ByteString;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 一条 relay 点对点连接，提供可靠或尽力而为的数据传输。
 * <p>
 * 连接通过状态机管理生命周期：{@link RelayState#CLOSED} → {@link RelayState#OPENING} →
 * {@link RelayState#OPEN} → {@link RelayState#CLOSING} → {@link RelayState#CLOSED}。
 * 发送数据使用滑动窗口和可配置的可靠性等级。
 */
public class RelayConnection {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Relay relay;
    private final String relayId;
    private volatile RelayState state;
    private final RelayOptions options;

    private UserRef remotePeer;
    private SessionRef remoteSession;
    private final SessionRef mySession;

    // 发送/接收队列
    private final BlockingQueue<byte[]> sendQueue;
    private final BlockingQueue<byte[]> recvQueue;

    // 打开完成信号
    private final CompletableFuture<Void> openFuture = new CompletableFuture<>();

    // 关闭标志
    private volatile boolean closed;

    // 发送线程
    private volatile Thread sendThread;

    // 状态锁 — 保护 state / retransCnt / unacked / sendBase / nextSeq / recvBuf / expectedSeq / onCloseHandlers
    private final Object stateLock = new Object();

    // 滑动窗口状态
    private long sendBase;
    private long nextSeq;
    private final Map<Long, UnackedFrame> unacked = new HashMap<>();
    private int retransCnt;

    // 有序接收缓存 (ReliableOrdered)
    private final Map<Long, byte[]> recvBuf = new HashMap<>();
    private long expectedSeq = 1;

    // 关闭回调
    private final List<Consumer<Throwable>> onCloseHandlers = new ArrayList<>();

    // 空闲超时追踪
    private volatile long lastActivityMs;

    // ---------- 包级构造（由 Relay 创建） ----------

    RelayConnection(Relay relay, String relayId, UserRef remotePeer,
                    SessionRef remoteSession, SessionRef mySession, RelayOptions options) {
        this.relay = Objects.requireNonNull(relay, "relay");
        this.relayId = Objects.requireNonNull(relayId, "relayId");
        this.state = RelayState.CLOSED;
        this.options = options != null ? options : RelayOptions.defaults();
        this.remotePeer = Objects.requireNonNull(remotePeer, "remotePeer");
        this.remoteSession = Objects.requireNonNull(remoteSession, "remoteSession");
        this.mySession = Objects.requireNonNull(mySession, "mySession");
        int queueCapacity = Math.max(1, this.options.sendBufferSize() / 1024);
        this.sendQueue = new LinkedBlockingQueue<>(queueCapacity);
        this.recvQueue = new LinkedBlockingQueue<>(64);
        this.lastActivityMs = System.currentTimeMillis();
    }

    // ---------- 公共 API ----------

    /** 返回连接的唯一标识。 */
    public String relayId() { return relayId; }

    /** 返回当前连接状态。 */
    public RelayState state() { return state; }

    /** 返回对端用户引用。 */
    public UserRef remotePeer() { return remotePeer; }

    /** 返回对端会话引用。 */
    public SessionRef remoteSession() { return remoteSession; }

    /** 返回本端会话引用。 */
    SessionRef mySession() { return mySession; }

    /** 返回 OPEN_ACK 等待 future（包级访问）。 */
    CompletableFuture<Void> openFuture() { return openFuture; }

    /**
     * 发送数据。行为取决于配置的可靠性等级。
     * <p>
     * 返回的 CompletableFuture 在数据成功入队后完成，若连接未打开则立即失败。
     * 当配置了 sendTimeoutMs（{@link RelayOptions.Builder#sendTimeoutMs(long)}）且缓冲区满时，
     * 等待超时后以 {@link RelayError#SEND_TIMEOUT} 失败。
     *
     * @param data 要发送的数据
     * @return 表示发送操作结果的 CompletableFuture
     */
    public CompletableFuture<Void> send(byte[] data) {
        if (data == null || data.length == 0) {
            return CompletableFuture.completedFuture(null);
        }
        if (state != RelayState.OPEN) {
            return CompletableFuture.failedFuture(
                new RelayError(RelayError.NOT_CONNECTED, "connection not open"));
        }
        try {
            byte[] cloned = data.clone();
            long sendTimeoutMs = options.sendTimeoutMs();
            if (sendTimeoutMs > 0) {
                boolean ok = sendQueue.offer(cloned, sendTimeoutMs, TimeUnit.MILLISECONDS);
                if (!ok) {
                    return CompletableFuture.failedFuture(
                        new RelayError(RelayError.SEND_TIMEOUT, "send timeout waiting for buffer space"));
                }
            } else {
                sendQueue.put(cloned);
            }
            return CompletableFuture.completedFuture(null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * 接收数据的阻塞队列。从队列读取对端发送的数据。
     *
     * @return 接收队列
     */
    public BlockingQueue<byte[]> receive() {
        return recvQueue;
    }

    /**
     * 从连接读取数据，支持超时。
     * <p>
     * timeoutMs 为 0 时无限等待（等价于 {@code receive().take()}）。
     * timeoutMs 大于 0 时等待指定毫秒数，超时后抛出 {@link RelayError#RECEIVE_TIMEOUT}。
     *
     * @param timeoutMs 等待超时毫秒数，0 表示无限等待
     * @return 接收到的数据
     * @throws InterruptedException 线程被中断
     * @throws RelayError           超时或连接已关闭
     */
    public byte[] receiveTimeout(long timeoutMs) throws InterruptedException {
        byte[] data = timeoutMs > 0
            ? recvQueue.poll(timeoutMs, TimeUnit.MILLISECONDS)
            : recvQueue.take();
        if (data == null) {
            throw new RelayError(RelayError.RECEIVE_TIMEOUT, "receive timeout");
        }
        return data;
    }

    /**
     * 注册连接关闭回调。
     *
     * @param handler 关闭回调，参数为关闭原因（正常关闭时为 null）
     */
    public void onClose(Consumer<Throwable> handler) {
        synchronized (stateLock) {
            onCloseHandlers.add(handler);
        }
    }

    /**
     * 优雅关闭连接，发送 CLOSE 帧。
     */
    public void close() {
        if (state != RelayState.OPEN) {
            return;
        }
        state = RelayState.CLOSING;

        RelayEnvelope closeEnv = new RelayEnvelope(
            relayId, RelayKind.CLOSE, mySession, remoteSession,
            0, 0, null, System.currentTimeMillis()
        );
        sendRelayEnvelope(closeEnv);

        handleClose(null);
    }

    /**
     * 强制关闭连接，不发送 CLOSE 帧。
     *
     * @param reason 关闭原因
     */
    public void abort(Throwable reason) {
        handleClose(reason);
    }

    // ---------- 包级方法 ----------

    /** 返回连接关联的 relay 管理器。 */
    Relay relay() { return relay; }

    /** 设置连接状态为 OPENING（由 Relay.connect 调用时使用）。 */
    void setOpening() {
        this.state = RelayState.OPENING;
    }

    /** 启动 sendLoop 线程。 */
    void startSendLoop() {
        sendThread = Thread.ofPlatform().daemon(true)
            .name("relay-send-" + relayId)
            .start(this::sendLoop);
    }

    /** 处理 OPEN_ACK 帧。 */
    void handleOpenAck(RelayEnvelope env) {
        synchronized (stateLock) {
            if (state != RelayState.OPENING) return;
            state = RelayState.OPEN;
            this.remoteSession = env.senderSession();
        }
        openFuture.complete(null);
    }

    /** 将入站连接的状态设为 OPEN（由 Relay.acceptIncoming 调用）。 */
    void markOpen() {
        this.state = RelayState.OPEN;
        openFuture.complete(null);
    }

    /** 处理各种 relay 帧（DATA/ACK/PING 等）。 */
    void handleEnvelope(RelayEnvelope env) {
        switch (env.kind()) {
            case DATA -> handleData(env);
            case ACK -> handleAck(env);
            case PING -> handlePing(env);
            default -> { /* 忽略未知类型 */ }
        }
    }

    /** 处理关闭帧或本地错误。 */
    void handleClose(Throwable reason) {
        synchronized (stateLock) {
            if (state == RelayState.CLOSED) return;
            state = RelayState.CLOSED;
        }
        this.closed = true;

        // 中断发送线程
        Thread st = sendThread;
        if (st != null) {
            st.interrupt();
        }

        // 完成 openFuture（防止等待者永远阻塞）
        openFuture.completeExceptionally(
            reason != null ? reason : new RelayError(RelayError.CLIENT_CLOSED, "connection closed locally"));

        // 复制并运行关闭回调
        List<Consumer<Throwable>> callbacks;
        synchronized (stateLock) {
            callbacks = new ArrayList<>(onCloseHandlers);
            onCloseHandlers.clear();
        }
        for (Consumer<Throwable> cb : callbacks) {
            try {
                cb.accept(reason);
            } catch (Exception ignored) {
                // 回调异常不影响其他回调执行
            }
        }

        // 从 relay 管理器中移除
        relay.removeConnection(relayId);
    }

    // ---------- 私有方法 ----------

    /** 发送 relay 信封（包装为 Packet 发送）。 */
    private CompletableFuture<Void> sendRelayEnvelope(RelayEnvelope env) {
        if (closed) {
            return CompletableFuture.failedFuture(
                new RelayError(RelayError.CLIENT_CLOSED, "connection closed"));
        }
        try {
            byte[] body = RelayEnvelope.encode(env);
            DeliveryMode mode = options.deliveryMode() != null
                ? options.deliveryMode() : DeliveryMode.ROUTE_RETRY;
            return relay.client().sendPacketToSession(remotePeer, remoteSession, body, mode)
                .thenApply(ignored -> null);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /** 发送 relay 信封（fire-and-forget，错误时关闭连接）。 */
    private void sendRelayEnvelopeFireAndForget(RelayEnvelope env) {
        sendRelayEnvelope(env).whenComplete((v, err) -> {
            if (err != null) {
                handleClose(new RelayError(RelayError.PROTOCOL,
                    "send failed: " + err.getMessage()));
            }
        });
    }

    /** 发送循环 — 从 sendQueue 读取数据并发送。 */
    private void sendLoop() {
        while (!closed && !Thread.currentThread().isInterrupted()) {
            try {
                // 使用 ackTimeout 作为 poll 超时，这样在无数据时也可触发重传检查
                long pollTimeout = options.ackTimeoutMs();
                if (options.reliability() == RelayOptions.Reliability.BEST_EFFORT) {
                    pollTimeout = 1000;
                }
                byte[] data = sendQueue.poll(pollTimeout, TimeUnit.MILLISECONDS);

                if (closed || Thread.currentThread().isInterrupted()) break;

                if (data == null) {
                    // poll 超时 — 检查重传
                    if (options.reliability() != RelayOptions.Reliability.BEST_EFFORT) {
                        retransmit();
                    }
                    // 检查空闲超时
                    if (options.idleTimeoutMs() > 0
                        && System.currentTimeMillis() - lastActivityMs > options.idleTimeoutMs()) {
                        handleClose(new RelayError(RelayError.IDLE_TIMEOUT, "idle timeout"));
                        return;
                    }
                    continue;
                }

                lastActivityMs = System.currentTimeMillis();

                if (options.reliability() == RelayOptions.Reliability.BEST_EFFORT) {
                    // BestEffort：直接发送，无 seq，无 ACK
                    RelayEnvelope env = new RelayEnvelope(
                        relayId, RelayKind.DATA, mySession, remoteSession,
                        0, 0, data, System.currentTimeMillis()
                    );
                    sendRelayEnvelopeFireAndForget(env);
                    continue;
                }

                // AtLeastOnce / ReliableOrdered：带 seq 的可靠发送
                long seq;
                synchronized (stateLock) {
                    // 等待窗口空间
                    while (nextSeq - sendBase >= options.windowSize()) {
                        try {
                            stateLock.wait(options.ackTimeoutMs());
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        if (closed) return;
                        // 窗口满时检查是否需要重传
                        retransmit();
                    }

                    seq = nextSeq++;
                    unacked.put(seq, new UnackedFrame(data, 0));
                    if (sendBase == 0) {
                        sendBase = seq;
                    }
                }

                RelayEnvelope env = new RelayEnvelope(
                    relayId, RelayKind.DATA, mySession, remoteSession,
                    seq, 0, data, System.currentTimeMillis()
                );
                sendRelayEnvelopeFireAndForget(env);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /** 重传所有未确认的帧。 */
    private void retransmit() {
        synchronized (stateLock) {
            if (unacked.isEmpty()) return;

            retransCnt++;
            if (retransCnt > options.maxRetransmits()) {
                handleClose(new RelayError(RelayError.MAX_RETRANSMIT,
                    "max retransmits exceeded"));
                return;
            }

            for (Map.Entry<Long, UnackedFrame> entry : unacked.entrySet()) {
                long seq = entry.getKey();
                byte[] data = entry.getValue().data();
                RelayEnvelope env = new RelayEnvelope(
                    relayId, RelayKind.DATA, mySession, remoteSession,
                    seq, 0, data, System.currentTimeMillis()
                );
                // 重传使用 fire-and-forget 避免递归锁问题
                sendRelayEnvelope(env).whenComplete((v, err) -> {
                    if (err != null) {
                        handleClose(new RelayError(RelayError.PROTOCOL,
                            "retransmit failed: " + err.getMessage()));
                    }
                });
            }
        }
    }

    /** 处理收到的 DATA 帧。 */
    private void handleData(RelayEnvelope env) {
        switch (options.reliability()) {
            case BEST_EFFORT -> {
                recvQueue.offer(env.payload());
            }

            case AT_LEAST_ONCE -> {
                // 发送 ACK
                RelayEnvelope ackEnv = new RelayEnvelope(
                    relayId, RelayKind.ACK, mySession, remoteSession,
                    0, env.seq(), null, System.currentTimeMillis()
                );
                sendRelayEnvelopeFireAndForget(ackEnv);
                recvQueue.offer(env.payload());
            }

            case RELIABLE_ORDERED -> {
                synchronized (stateLock) {
                    if (env.seq() == expectedSeq) {
                        deliverOrdered(env.payload());
                        expectedSeq++;
                        // 递送所有连续的已缓存帧
                        while (true) {
                            byte[] buf = recvBuf.get(expectedSeq);
                            if (buf == null) break;
                            deliverOrdered(buf);
                            recvBuf.remove(expectedSeq);
                            expectedSeq++;
                        }
                    } else if (env.seq() > expectedSeq) {
                        // 乱序帧 — 缓存（在窗口内）
                        if (env.seq() - expectedSeq < options.windowSize()) {
                            recvBuf.put(env.seq(), env.payload());
                        }
                    }
                }

                // 发送 ACK
                RelayEnvelope ackEnv = new RelayEnvelope(
                    relayId, RelayKind.ACK, mySession, remoteSession,
                    0, env.seq(), null, System.currentTimeMillis()
                );
                sendRelayEnvelopeFireAndForget(ackEnv);
            }
        }
    }

    /** 有序递送（非阻塞，满则丢弃）。 */
    private void deliverOrdered(byte[] data) {
        recvQueue.offer(data);
    }

    /** 处理 ACK 帧 — 从未确认表中移除已确认的 seq。 */
    private void handleAck(RelayEnvelope env) {
        if (options.reliability() == RelayOptions.Reliability.BEST_EFFORT) {
            return;
        }
        synchronized (stateLock) {
            if (env.ackSeq() >= sendBase) {
                for (long s = sendBase; s <= env.ackSeq(); s++) {
                    unacked.remove(s);
                }
                sendBase = env.ackSeq() + 1;
                retransCnt = 0;
                stateLock.notifyAll();
            }
        }
    }

    /** 处理 PING 帧 — 回 ERROR。 */
    private void handlePing(RelayEnvelope env) {
        RelayEnvelope errEnv = new RelayEnvelope(
            relayId, RelayKind.ERROR, mySession, remoteSession,
            0, 0, null, System.currentTimeMillis()
        );
        sendRelayEnvelopeFireAndForget(errEnv);
    }

    // ---------- 内部类型 ----------

    /**
     * Relay 协议的帧类型，对应 proto RelayEnvelope。
     *
     * @param relayId        连接标识
     * @param kind           帧类型
     * @param senderSession  发送方会话引用
     * @param targetSession  目标会话引用
     * @param seq            序列号（Data 帧使用）
     * @param ackSeq         确认的序列号（Ack 帧使用）
     * @param payload        数据载荷
     * @param sentAtMs       发送时间戳（毫秒）
     */
    public record RelayEnvelope(
        String relayId,
        RelayKind kind,
        SessionRef senderSession,
        SessionRef targetSession,
        long seq,
        long ackSeq,
        byte[] payload,
        long sentAtMs
    ) {
        /**
         * 将 RelayEnvelope 编码为 protobuf 字节数组。
         *
         * @param env 信封
         * @return protobuf 编码的字节数组
         */
        public static byte[] encode(RelayEnvelope env) {
            notifier.relay.v1.Relay.RelayEnvelope.Builder builder =
                notifier.relay.v1.Relay.RelayEnvelope.newBuilder()
                    .setRelayId(env.relayId())
                    .setKind(toProtoKind(env.kind()))
                    .setSenderSession(
                        notifier.client.v1.Client.SessionRef.newBuilder()
                            .setServingNodeId(env.senderSession().servingNodeId())
                            .setSessionId(env.senderSession().sessionId())
                            .build())
                    .setTargetSession(
                        notifier.client.v1.Client.SessionRef.newBuilder()
                            .setServingNodeId(env.targetSession().servingNodeId())
                            .setSessionId(env.targetSession().sessionId())
                            .build())
                    .setSeq(env.seq())
                    .setAckSeq(env.ackSeq())
                    .setSentAtMs(env.sentAtMs());
            if (env.payload() != null) {
                builder.setPayload(ByteString.copyFrom(env.payload()));
            }
            return builder.build().toByteArray();
        }

        /**
         * 从 protobuf 字节数组解码 RelayEnvelope。
         *
         * @param data protobuf 编码的字节数组
         * @return 解码后的信封
         */
        public static RelayEnvelope decode(byte[] data) {
            notifier.relay.v1.Relay.RelayEnvelope pb;
            try {
                pb = notifier.relay.v1.Relay.RelayEnvelope.parseFrom(data);
            } catch (Exception e) {
                throw new RuntimeException("failed to decode RelayEnvelope", e);
            }
            return new RelayEnvelope(
                pb.getRelayId(),
                fromProtoKind(pb.getKind()),
                new SessionRef(
                    pb.getSenderSession().getServingNodeId(),
                    pb.getSenderSession().getSessionId()),
                new SessionRef(
                    pb.getTargetSession().getServingNodeId(),
                    pb.getTargetSession().getSessionId()),
                pb.getSeq(),
                pb.getAckSeq(),
                pb.getPayload().toByteArray(),
                pb.getSentAtMs()
            );
        }

        private static notifier.relay.v1.Relay.RelayKind toProtoKind(RelayKind kind) {
            return switch (kind) {
                case OPEN -> notifier.relay.v1.Relay.RelayKind.RELAY_KIND_OPEN;
                case OPEN_ACK -> notifier.relay.v1.Relay.RelayKind.RELAY_KIND_OPEN_ACK;
                case DATA -> notifier.relay.v1.Relay.RelayKind.RELAY_KIND_DATA;
                case ACK -> notifier.relay.v1.Relay.RelayKind.RELAY_KIND_ACK;
                case CLOSE -> notifier.relay.v1.Relay.RelayKind.RELAY_KIND_CLOSE;
                case PING -> notifier.relay.v1.Relay.RelayKind.RELAY_KIND_PING;
                case ERROR -> notifier.relay.v1.Relay.RelayKind.RELAY_KIND_ERROR;
                default -> notifier.relay.v1.Relay.RelayKind.RELAY_KIND_UNSPECIFIED;
            };
        }

        private static RelayKind fromProtoKind(notifier.relay.v1.Relay.RelayKind pbKind) {
            return switch (pbKind) {
                case RELAY_KIND_OPEN -> RelayKind.OPEN;
                case RELAY_KIND_OPEN_ACK -> RelayKind.OPEN_ACK;
                case RELAY_KIND_DATA -> RelayKind.DATA;
                case RELAY_KIND_ACK -> RelayKind.ACK;
                case RELAY_KIND_CLOSE -> RelayKind.CLOSE;
                case RELAY_KIND_PING -> RelayKind.PING;
                case RELAY_KIND_ERROR -> RelayKind.ERROR;
                default -> RelayKind.UNSPECIFIED;
            };
        }
    }

    /**
     * Relay 协议帧类型枚举，对应 proto RelayKind。
     */
    public enum RelayKind {
        UNSPECIFIED,
        OPEN,
        OPEN_ACK,
        DATA,
        ACK,
        CLOSE,
        PING,
        ERROR
    }

    /** 未确认的帧（发送窗口中的条目）。 */
    private record UnackedFrame(byte[] data, int retransmitCount) {
    }

    /** 生成随机 relay ID。 */
    static String newRelayId() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }
}
