package io.github.tursom.turntf.java;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MemoryCursorStore implements CursorStore {
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
        messages.putIfAbsent(cursor, new Message(
            new UserRef(0, 0),
            cursor.nodeId(),
            cursor.seq(),
            new UserRef(0, 0),
            new byte[0],
            ""
        ));
        if (!order.contains(cursor)) {
            order.add(cursor);
        }
    }

    public synchronized boolean hasCursor(MessageCursor cursor) {
        return messages.containsKey(cursor);
    }

    public synchronized Message message(MessageCursor cursor) {
        return messages.get(cursor);
    }
}
