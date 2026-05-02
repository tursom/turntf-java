package io.github.tursom.turntf.java;

/**
 * {@link ClientListener} 的空操作实现，所有回调方法均不执行任何操作。
 * <p>
 * 当调用方只关心部分回调事件时，可以继承此类并重写感兴趣的方法，
 * 而不必实现接口中的所有方法。例如：
 * <pre>{@code
 * ClientListener listener = new NopClientListener() {
 *     @Override
 *     public void onMessage(Message message) {
 *         System.out.println("收到消息: " + message);
 *     }
 * };
 * }</pre>
 */
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
