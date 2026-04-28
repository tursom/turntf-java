package io.github.tursom.turntf.java;

import java.util.List;

public record ResolvedUserSessions(
    UserRef user,
    List<OnlineNodePresence> presence,
    List<ResolvedSession> sessions
) {
    public record OnlineNodePresence(long servingNodeId, int sessionCount, String transportHint) {
    }

    public record ResolvedSession(SessionRef session, String transport, boolean transientCapable) {
    }
}
