package io.github.tursom.turntf.java;

import java.util.List;

public interface CursorStore {
    List<MessageCursor> loadSeenMessages();

    void saveMessage(Message message);

    void saveCursor(MessageCursor cursor);
}
