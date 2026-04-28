package io.github.tursom.turntf.java;

public class NopClientListener implements ClientListener {
    @Override
    public void onLogin(LoginInfo info) {
    }

    @Override
    public void onMessage(Message message) {
    }

    @Override
    public void onPacket(Packet packet) {
    }

    @Override
    public void onError(Throwable error) {
    }

    @Override
    public void onDisconnect(Throwable error) {
    }
}
