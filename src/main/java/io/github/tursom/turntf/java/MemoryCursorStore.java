package io.github.tursom.turntf.java;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于内存的 {@link CursorStore} 实现，适用于测试、演示和短期运行进程。
 * <p>
 * 使用 {@link LinkedHashMap} 保持插入顺序，同时支持 O(1) 的游标查找。
 * 所有公共方法均为同步方法（synchronized），保障线程安全。
 * <p>
 * <b>注意：</b>该实现不会持久化数据，进程重启后所有数据将丢失。
 * 生产环境应使用基于文件或数据库的持久化实现。
 */
public final class MemoryCursorStore implements CursorStore {
    // LinkedHashMap 保持插入顺序以便于内存检测，同时支持通过游标快速查找消息
    private final Map<MessageCursor, Message> messages = new LinkedHashMap<>();
    private final List<MessageCursor> order = new ArrayList<>();

    @Override
    public synchronized List<MessageCursor> loadSeenMessages() {
        return new ArrayList<>(order);
    }

    @Override
    public synchronized void saveMessage(Message message) {
        messages.put(message.cursor(), message);
    }

    @Override
    public synchronized void saveCursor(MessageCursor cursor) {
        // saveCursor 可能在正常推送中在 saveMessage 之后调用，
        // 但重连恢复只需要稳定的游标列表。保留一条合成的 Message
        // 可以让测试和调试器检查存储状态，而无需特殊处理"仅游标"条目。
        messages.putIfAbsent(cursor, new Message(
            new UserRef(0, 0),
            cursor.nodeId(),
            cursor.seq(),
            new UserRef(0, 0),
            new byte[0],
            ""
        ));
        // 登录握手会按 loadSeenMessages() 返回的顺序重放已见的游标。
        // 重复游标只会使重连帧臃肿并增加恢复推理的复杂度。
        if (!order.contains(cursor)) {
            order.add(cursor);
        }
    }

    /**
     * 检查指定的消息游标是否已被记录。
     *
     * @param cursor 要检查的消息游标
     * @return 如果游标已存在则返回 {@code true}
     */
    public synchronized boolean hasCursor(MessageCursor cursor) {
        return messages.containsKey(cursor);
    }

    /**
     * 根据游标获取已保存的消息。
     *
     * @param cursor 要查询的消息游标
     * @return 对应的消息对象，如果不存在则返回 {@code null}
     */
    public synchronized Message message(MessageCursor cursor) {
        return messages.get(cursor);
    }
}
