package io.github.tursom.turntf.java;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory {@link CursorStore} implementation for tests, demos, and short-lived processes.
 */
public final class MemoryCursorStore implements CursorStore {
    // LinkedHashMap preserves insertion order for in-memory inspection while still allowing the
    // store to answer "have we seen this cursor?" without scanning the order list.
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
        // saveCursor can run after saveMessage for normal pushes, but reconnect recovery only
        // needs a stable cursor list. Keeping a synthetic Message lets tests and debuggers inspect
        // the store without having to special-case "cursor-only" entries.
        messages.putIfAbsent(cursor, new Message(
            new UserRef(0, 0),
            cursor.nodeId(),
            cursor.seq(),
            new UserRef(0, 0),
            new byte[0],
            ""
        ));
        // The login handshake replays seen cursors in the order returned by loadSeenMessages().
        // Duplicates would only bloat the reconnect frame and make recovery reasoning harder.
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
