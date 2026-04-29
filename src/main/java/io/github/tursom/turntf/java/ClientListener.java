package io.github.tursom.turntf.java;

/**
 * Receives realtime lifecycle and delivery callbacks from {@link TurntfClient}.
 *
 * <p>Callbacks are emitted after the SDK has finished the protocol work required for the
 * corresponding frame. In particular, {@link #onMessage(Message)} runs only after the client has
 * attempted local persistence and, when enabled, sent the ack frame back to the server.
 */
public interface ClientListener {
    void onLogin(LoginInfo info);

    void onMessage(Message message);

    void onPacket(Packet packet);

    void onError(Throwable error);

    void onDisconnect(Throwable error);
}
