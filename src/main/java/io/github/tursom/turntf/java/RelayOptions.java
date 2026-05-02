package io.github.tursom.turntf.java;

import java.util.Objects;

/**
 * RelayConnection 的配置项，使用 Builder 模式构建。
 * <p>
 * 通过 {@link #builder()} 创建构建器，调用各 setter 链式设值，最后调用 {@link Builder#build()} 得到不可变实例。
 */
public class RelayOptions {
    /** 可靠性等级。 */
    public enum Reliability {
        /** 无 ACK，无重传，无去重，无排序。延迟最低，适合实时音视频帧。 */
        BEST_EFFORT,
        /** ACK + 重传，不保证去重和排序。适合幂等指令。 */
        AT_LEAST_ONCE,
        /** ACK + 重传 + 去重 + 严格有序。适合文件传输和聊天消息。 */
        RELIABLE_ORDERED
    }

    private final Reliability reliability;
    private final int windowSize;
    private final long openTimeoutMs;
    private final long closeTimeoutMs;
    private final long ackTimeoutMs;
    private final int maxRetransmits;
    private final long idleTimeoutMs;
    private final long sendTimeoutMs;
    private final long receiveTimeoutMs;
    private final int sendBufferSize;
    private final DeliveryMode deliveryMode;

    private RelayOptions(Builder builder) {
        this.reliability = Objects.requireNonNull(builder.reliability, "reliability");
        this.windowSize = builder.windowSize;
        this.openTimeoutMs = builder.openTimeoutMs;
        this.closeTimeoutMs = builder.closeTimeoutMs;
        this.ackTimeoutMs = builder.ackTimeoutMs;
        this.maxRetransmits = builder.maxRetransmits;
        this.idleTimeoutMs = builder.idleTimeoutMs;
        this.sendTimeoutMs = builder.sendTimeoutMs;
        this.receiveTimeoutMs = builder.receiveTimeoutMs;
        this.sendBufferSize = builder.sendBufferSize;
        this.deliveryMode = Objects.requireNonNull(builder.deliveryMode, "deliveryMode");
    }

    public Reliability reliability() { return reliability; }
    public int windowSize() { return windowSize; }
    public long openTimeoutMs() { return openTimeoutMs; }
    public long closeTimeoutMs() { return closeTimeoutMs; }
    public long ackTimeoutMs() { return ackTimeoutMs; }
    public int maxRetransmits() { return maxRetransmits; }
    public long idleTimeoutMs() { return idleTimeoutMs; }
    public long sendTimeoutMs() { return sendTimeoutMs; }
    public long receiveTimeoutMs() { return receiveTimeoutMs; }
    public int sendBufferSize() { return sendBufferSize; }
    public DeliveryMode deliveryMode() { return deliveryMode; }

    public static Builder builder() {
        return new Builder();
    }

    /** 返回带默认值的 RelayOptions。 */
    public static RelayOptions defaults() {
        return builder().build();
    }

    /** RelayOptions 的构建器。 */
    public static class Builder {
        private Reliability reliability = Reliability.RELIABLE_ORDERED;
        private int windowSize = 16;
        private long openTimeoutMs = 10000;
        private long closeTimeoutMs = 5000;
        private long ackTimeoutMs = 3000;
        private int maxRetransmits = 5;
        private long idleTimeoutMs = 0;
        private long sendTimeoutMs = 0;
        private long receiveTimeoutMs = 0;
        private int sendBufferSize = 65536;
        private DeliveryMode deliveryMode = DeliveryMode.ROUTE_RETRY;

        Builder() {}

        public Builder reliability(Reliability reliability) {
            this.reliability = reliability;
            return this;
        }

        /** 发送窗口大小（在途未确认帧数上限），范围 1-256，默认 16。BestEffort 模式下忽略。 */
        public Builder windowSize(int windowSize) {
            if (windowSize < 1 || windowSize > 256) {
                throw new IllegalArgumentException("windowSize must be 1-256");
            }
            this.windowSize = windowSize;
            return this;
        }

        /** OPEN 等待 OPEN_ACK 超时毫秒数，默认 10000。 */
        public Builder openTimeoutMs(long openTimeoutMs) {
            this.openTimeoutMs = openTimeoutMs;
            return this;
        }

        /** CLOSE 等待确认超时毫秒数，默认 5000。 */
        public Builder closeTimeoutMs(long closeTimeoutMs) {
            this.closeTimeoutMs = closeTimeoutMs;
            return this;
        }

        /** DATA 等待 ACK 超时毫秒数，默认 3000。BestEffort 模式下忽略。 */
        public Builder ackTimeoutMs(long ackTimeoutMs) {
            this.ackTimeoutMs = ackTimeoutMs;
            return this;
        }

        /** 最大重传次数，默认 5。BestEffort 模式下忽略。 */
        public Builder maxRetransmits(int maxRetransmits) {
            this.maxRetransmits = maxRetransmits;
            return this;
        }

        /** 无数据超时断开毫秒数，0 表示不超时。 */
        public Builder idleTimeoutMs(long idleTimeoutMs) {
            this.idleTimeoutMs = idleTimeoutMs;
            return this;
        }

        /** Send 操作超时毫秒数（缓冲区满时等待上限），0 表示不超时。 */
        public Builder sendTimeoutMs(long sendTimeoutMs) {
            this.sendTimeoutMs = sendTimeoutMs;
            return this;
        }

        /** Receive 操作超时毫秒数（无数据等待上限），0 表示不超时。 */
        public Builder receiveTimeoutMs(long receiveTimeoutMs) {
            this.receiveTimeoutMs = receiveTimeoutMs;
            return this;
        }

        /** 发送缓冲区字节数，默认 65536。 */
        public Builder sendBufferSize(int sendBufferSize) {
            this.sendBufferSize = sendBufferSize;
            return this;
        }

        /** Packet 投递模式，默认 DeliveryMode.ROUTE_RETRY。 */
        public Builder deliveryMode(DeliveryMode deliveryMode) {
            this.deliveryMode = deliveryMode;
            return this;
        }

        public RelayOptions build() {
            return new RelayOptions(this);
        }
    }
}
