package io.github.tursom.turntf.java;

import java.util.List;

public interface CursorStore {
    /**
     * Returns the cursors that were durably observed by the client and can be announced again
     * on the next websocket login. The server uses this list to suppress re-delivery after
     * reconnect, so implementations should preserve order and avoid dropping entries until the
     * application is willing to replay them.
     */
    List<MessageCursor> loadSeenMessages();

    /**
     * Persists the full message payload when it is available locally. This is called before the
     * client acks pushed messages so a crash does not leave the server believing the message was
     * consumed while the client lost the body.
     */
    void saveMessage(Message message);

    /**
     * Marks a cursor as seen even when the full payload is not available anymore. The reconnect
     * handshake only needs the cursor, so stores may record a lightweight placeholder here.
     */
    void saveCursor(MessageCursor cursor);
}
