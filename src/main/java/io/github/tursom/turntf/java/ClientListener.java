package io.github.tursom.turntf.java;

public interface ClientListener {
    void onLogin(LoginInfo info);

    void onMessage(Message message);

    void onPacket(Packet packet);

    void onError(Throwable error);

    void onDisconnect(Throwable error);
}
