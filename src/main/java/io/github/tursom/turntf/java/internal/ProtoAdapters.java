package io.github.tursom.turntf.java.internal;

import io.github.tursom.turntf.java.Attachment;
import io.github.tursom.turntf.java.AttachmentType;
import io.github.tursom.turntf.java.BlacklistEntry;
import io.github.tursom.turntf.java.ClusterNode;
import io.github.tursom.turntf.java.DeleteUserResult;
import io.github.tursom.turntf.java.DeliveryMode;
import io.github.tursom.turntf.java.Event;
import io.github.tursom.turntf.java.LoggedInUser;
import io.github.tursom.turntf.java.LoginInfo;
import io.github.tursom.turntf.java.Message;
import io.github.tursom.turntf.java.MessageCursor;
import io.github.tursom.turntf.java.OperationsStatus;
import io.github.tursom.turntf.java.Packet;
import io.github.tursom.turntf.java.PasswordInput;
import io.github.tursom.turntf.java.ProtocolError;
import io.github.tursom.turntf.java.RelayAccepted;
import io.github.tursom.turntf.java.ResolvedUserSessions;
import io.github.tursom.turntf.java.SessionRef;
import io.github.tursom.turntf.java.Subscription;
import io.github.tursom.turntf.java.UpdateUserRequest;
import io.github.tursom.turntf.java.User;
import io.github.tursom.turntf.java.UserMetadata;
import io.github.tursom.turntf.java.UserMetadataScanResult;
import io.github.tursom.turntf.java.UserRef;
import java.util.ArrayList;
import java.util.List;
import notifier.client.v1.Client;
import com.google.protobuf.ByteString;

/**
 * Converts generated protobuf messages into the SDK's public Java model and back.
 *
 * <p>The adapters intentionally absorb wire-level quirks such as absent nested messages, signed
 * JVM longs standing in for unsigned protobuf identifiers, and enum values that may drift over
 * time.
 */
public final class ProtoAdapters {
    private ProtoAdapters() {
    }

    public static Client.UserRef toProto(UserRef input) {
        return Client.UserRef.newBuilder().setNodeId(input.nodeId()).setUserId(input.userId()).build();
    }

    public static UserRef fromProto(Client.UserRef input) {
        if (input == null) {
            // Zero refs are used throughout the client as a sentinel for "field absent on the
            // wire" so downstream code can stay null-free even when proto optional messages are
            // omitted.
            return new UserRef(0, 0);
        }
        return new UserRef(input.getNodeId(), input.getUserId());
    }

    public static Client.SessionRef toProto(SessionRef input) {
        return Client.SessionRef.newBuilder()
            .setServingNodeId(input.servingNodeId())
            .setSessionId(input.sessionId())
            .build();
    }

    public static SessionRef fromProto(Client.SessionRef input) {
        if (input == null) {
            // Session targeting is optional for transient packets; preserve that distinction with a
            // stable zero-valued SessionRef instead of leaking null checks into call sites.
            return new SessionRef(0, "");
        }
        return new SessionRef(input.getServingNodeId(), input.getSessionId());
    }

    public static Client.MessageCursor toProto(MessageCursor input) {
        return Client.MessageCursor.newBuilder().setNodeId(input.nodeId()).setSeq(input.seq()).build();
    }

    public static MessageCursor fromProto(Client.MessageCursor input) {
        if (input == null) {
            return new MessageCursor(0, 0);
        }
        return new MessageCursor(input.getNodeId(), input.getSeq());
    }

    public static User fromProto(Client.User input) {
        if (input == null) {
            return new User(0, 0, "", "", new byte[0], false, "", "", 0, "");
        }
        return new User(
            input.getNodeId(),
            input.getUserId(),
            input.getUsername(),
            input.getRole(),
            input.getProfileJson().toByteArray(),
            input.getSystemReserved(),
            input.getCreatedAt(),
            input.getUpdatedAt(),
            input.getOriginNodeId(),
            input.getLoginName()
        );
    }

    public static Message fromProto(Client.Message input) {
        if (input == null) {
            return new Message(new UserRef(0, 0), 0, 0, new UserRef(0, 0), new byte[0], "");
        }
        return new Message(
            fromProto(input.getRecipient()),
            input.getNodeId(),
            input.getSeq(),
            fromProto(input.getSender()),
            input.getBody().toByteArray(),
            input.getCreatedAtHlc()
        );
    }

    public static Packet fromProto(Client.Packet input) {
        return new Packet(
            // packet_id is the only stable identifier a transient sender gets back, so reject
            // negative values early instead of letting them disappear into application logs.
            Validation.requireUnsigned(input.getPacketId(), "packet_id"),
            input.getSourceNodeId(),
            input.getTargetNodeId(),
            fromProto(input.getRecipient()),
            fromProto(input.getSender()),
            input.getBody().toByteArray(),
            fromProto(input.getDeliveryMode()),
            fromProto(input.getTargetSession())
        );
    }

    public static RelayAccepted fromProto(Client.TransientAccepted input) {
        return new RelayAccepted(
            Validation.requireUnsigned(input.getPacketId(), "packet_id"),
            input.getSourceNodeId(),
            input.getTargetNodeId(),
            fromProto(input.getRecipient()),
            fromProto(input.getDeliveryMode()),
            fromProto(input.getTargetSession())
        );
    }

    public static Client.ClientDeliveryMode toProto(DeliveryMode mode) {
        return switch (mode) {
            case BEST_EFFORT -> Client.ClientDeliveryMode.CLIENT_DELIVERY_MODE_BEST_EFFORT;
            case ROUTE_RETRY -> Client.ClientDeliveryMode.CLIENT_DELIVERY_MODE_ROUTE_RETRY;
            // UNSPECIFIED is meaningful for persistent messages: the server chooses its default
            // semantics there, while transient packets must opt into an explicit relay policy.
            default -> Client.ClientDeliveryMode.CLIENT_DELIVERY_MODE_UNSPECIFIED;
        };
    }

    public static DeliveryMode fromProto(Client.ClientDeliveryMode mode) {
        return switch (mode) {
            case CLIENT_DELIVERY_MODE_BEST_EFFORT -> DeliveryMode.BEST_EFFORT;
            case CLIENT_DELIVERY_MODE_ROUTE_RETRY -> DeliveryMode.ROUTE_RETRY;
            default -> DeliveryMode.UNSPECIFIED;
        };
    }

    public static Client.AttachmentType toProto(AttachmentType type) {
        if (type == null) {
            return Client.AttachmentType.ATTACHMENT_TYPE_UNSPECIFIED;
        }
        return switch (type) {
            case CHANNEL_MANAGER -> Client.AttachmentType.ATTACHMENT_TYPE_CHANNEL_MANAGER;
            case CHANNEL_WRITER -> Client.AttachmentType.ATTACHMENT_TYPE_CHANNEL_WRITER;
            case CHANNEL_SUBSCRIPTION -> Client.AttachmentType.ATTACHMENT_TYPE_CHANNEL_SUBSCRIPTION;
            case USER_BLACKLIST -> Client.AttachmentType.ATTACHMENT_TYPE_USER_BLACKLIST;
        };
    }

    public static AttachmentType fromProto(Client.AttachmentType type) {
        return switch (type) {
            case ATTACHMENT_TYPE_CHANNEL_MANAGER -> AttachmentType.CHANNEL_MANAGER;
            case ATTACHMENT_TYPE_CHANNEL_WRITER -> AttachmentType.CHANNEL_WRITER;
            case ATTACHMENT_TYPE_CHANNEL_SUBSCRIPTION -> AttachmentType.CHANNEL_SUBSCRIPTION;
            case ATTACHMENT_TYPE_USER_BLACKLIST -> AttachmentType.USER_BLACKLIST;
            // Preserve unknown/unspecified attachment tags as null so callers can detect protocol
            // drift instead of silently mapping them onto the wrong local enum.
            default -> null;
        };
    }

    public static Attachment fromProto(Client.Attachment input) {
        return new Attachment(
            fromProto(input.getOwner()),
            fromProto(input.getSubject()),
            fromProto(input.getAttachmentType()),
            input.getConfigJson().toByteArray(),
            input.getAttachedAt(),
            input.getDeletedAt(),
            input.getOriginNodeId()
        );
    }

    public static UserMetadata fromProto(Client.UserMetadata input) {
        if (input == null) {
            return new UserMetadata(new UserRef(0, 0), "", new byte[0], "", "", "", 0);
        }
        return new UserMetadata(
            fromProto(input.getOwner()),
            input.getKey(),
            input.getValue().toByteArray(),
            input.getUpdatedAt(),
            input.getDeletedAt(),
            input.getExpiresAt(),
            input.getOriginNodeId()
        );
    }

    public static Subscription subscriptionFromProto(Client.Attachment input) {
        Attachment attachment = fromProto(input);
        return new Subscription(attachment.owner(), attachment.subject(), attachment.attachedAt(), attachment.deletedAt(), attachment.originNodeId());
    }

    public static BlacklistEntry blacklistEntryFromProto(Client.Attachment input) {
        Attachment attachment = fromProto(input);
        return new BlacklistEntry(attachment.owner(), attachment.subject(), attachment.attachedAt(), attachment.deletedAt(), attachment.originNodeId());
    }

    public static Event fromProto(Client.Event input) {
        return new Event(
            input.getSequence(),
            input.getEventId(),
            input.getEventType(),
            input.getAggregate(),
            input.getAggregateNodeId(),
            input.getAggregateId(),
            input.getHlc(),
            input.getOriginNodeId(),
            input.getEventJson().toByteArray()
        );
    }

    public static ClusterNode fromProto(Client.ClusterNode input) {
        return new ClusterNode(input.getNodeId(), input.getIsLocal(), input.getConfiguredUrl(), input.getSource());
    }

    public static LoggedInUser fromProto(Client.LoggedInUser input) {
        return new LoggedInUser(input.getNodeId(), input.getUserId(), input.getUsername(), input.getLoginName());
    }

    public static ResolvedUserSessions fromProto(Client.ResolveUserSessionsResponse input) {
        List<ResolvedUserSessions.OnlineNodePresence> presence = new ArrayList<>();
        for (Client.OnlineNodePresence item : input.getPresenceList()) {
            presence.add(new ResolvedUserSessions.OnlineNodePresence(item.getServingNodeId(), item.getSessionCount(), item.getTransportHint()));
        }
        List<ResolvedUserSessions.ResolvedSession> sessions = new ArrayList<>();
        for (Client.ResolvedSession item : input.getItemsList()) {
            // Presence is a node-level summary, while items enumerate concrete sessions. Keeping
            // both surfaces intact lets callers decide whether they need coarse routing hints or a
            // direct session address for packet fan-out.
            sessions.add(new ResolvedUserSessions.ResolvedSession(fromProto(item.getSession()), item.getTransport(), item.getTransientCapable()));
        }
        return new ResolvedUserSessions(fromProto(input.getUser()), presence, sessions);
    }

    public static OperationsStatus fromProto(Client.OperationsStatus input) {
        List<OperationsStatus.PeerStatus> peers = new ArrayList<>();
        for (Client.PeerStatus peer : input.getPeersList()) {
            List<OperationsStatus.PeerOriginStatus> origins = new ArrayList<>();
            for (Client.PeerOriginStatus origin : peer.getOriginsList()) {
                // Replication progress is tracked per origin inside each peer because a node can be
                // fully caught up for one source and still be replaying another. Flattening that
                // information here would lose the key debugging dimension for snapshot/catch-up
                // issues.
                origins.add(new OperationsStatus.PeerOriginStatus(
                    origin.getOriginNodeId(),
                    origin.getAckedEventId(),
                    origin.getAppliedEventId(),
                    origin.getUnconfirmedEvents(),
                    origin.getCursorUpdatedAt(),
                    Validation.requireUnsigned(origin.getRemoteLastEventId(), "remote_last_event_id"),
                    origin.getPendingCatchup()
                ));
            }
            peers.add(new OperationsStatus.PeerStatus(
                peer.getNodeId(),
                peer.getConfiguredUrl(),
                peer.getSource(),
                peer.getDiscoveredUrl(),
                peer.getDiscoveryState(),
                peer.getLastDiscoveredAt(),
                peer.getLastConnectedAt(),
                peer.getLastDiscoveryError(),
                peer.getConnected(),
                peer.getSessionDirection(),
                origins,
                peer.getPendingSnapshotPartitions(),
                peer.getRemoteSnapshotVersion(),
                peer.getRemoteMessageWindowSize(),
                peer.getClockOffsetMs(),
                peer.getLastClockSync(),
                Validation.requireUnsigned(peer.getSnapshotDigestsSentTotal(), "snapshot_digests_sent_total"),
                Validation.requireUnsigned(peer.getSnapshotDigestsReceivedTotal(), "snapshot_digests_received_total"),
                Validation.requireUnsigned(peer.getSnapshotChunksSentTotal(), "snapshot_chunks_sent_total"),
                Validation.requireUnsigned(peer.getSnapshotChunksReceivedTotal(), "snapshot_chunks_received_total"),
                peer.getLastSnapshotDigestAt(),
                peer.getLastSnapshotChunkAt()
            ));
        }
        return new OperationsStatus(
            input.getNodeId(),
            input.getMessageWindowSize(),
            input.getLastEventSequence(),
            input.getWriteGateReady(),
            input.getConflictTotal(),
            new OperationsStatus.MessageTrimStatus(input.getMessageTrim().getTrimmedTotal(), input.getMessageTrim().getLastTrimmedAt()),
            new OperationsStatus.ProjectionStatus(input.getProjection().getPendingTotal(), input.getProjection().getLastFailedAt()),
            peers,
            new OperationsStatus.EventLogTrimStatus(input.getEventLogTrim().getTrimmedTotal(), input.getEventLogTrim().getLastTrimmedAt())
        );
    }

    public static LoginInfo loginInfo(Client.LoginResponse input) {
        return new LoginInfo(fromProto(input.getUser()), input.getProtocolVersion(), fromProto(input.getSessionRef()));
    }

    public static DeleteUserResult deleteUserResult(Client.DeleteUserResponse input) {
        return new DeleteUserResult(input.getStatus(), fromProto(input.getUser()));
    }

    public static UserMetadataScanResult fromProto(Client.ScanUserMetadataResponse input) {
        return new UserMetadataScanResult(
            userMetadataItems(input.getItemsList()),
            input.getCount(),
            input.getNextAfter()
        );
    }

    public static List<Message> messages(List<Client.Message> items) {
        List<Message> out = new ArrayList<>();
        for (Client.Message item : items) {
            out.add(fromProto(item));
        }
        return out;
    }

    public static List<Attachment> attachments(List<Client.Attachment> items) {
        List<Attachment> out = new ArrayList<>();
        for (Client.Attachment item : items) {
            out.add(fromProto(item));
        }
        return out;
    }

    public static List<UserMetadata> userMetadataItems(List<Client.UserMetadata> items) {
        List<UserMetadata> out = new ArrayList<>();
        for (Client.UserMetadata item : items) {
            out.add(fromProto(item));
        }
        return out;
    }

    public static List<Event> events(List<Client.Event> items) {
        List<Event> out = new ArrayList<>();
        for (Client.Event item : items) {
            out.add(fromProto(item));
        }
        return out;
    }

    public static List<ClusterNode> clusterNodes(List<Client.ClusterNode> items) {
        List<ClusterNode> out = new ArrayList<>();
        for (Client.ClusterNode item : items) {
            out.add(fromProto(item));
        }
        return out;
    }

    public static List<LoggedInUser> loggedInUsers(List<Client.LoggedInUser> items) {
        List<LoggedInUser> out = new ArrayList<>();
        for (Client.LoggedInUser item : items) {
            out.add(fromProto(item));
        }
        return out;
    }

    public static Client.StringField optionalStringField(String value) {
        return value == null ? null : Client.StringField.newBuilder().setValue(value).build();
    }

    public static Client.StringField optionalPasswordField(PasswordInput value) {
        return value == null ? null : Client.StringField.newBuilder().setValue(value.wireValue()).build();
    }

    public static Client.BytesField optionalBytesField(byte[] value) {
        // Proto patch-style update messages distinguish "field omitted" from "field set to empty
        // bytes". Returning null for absent inputs preserves that distinction.
        return value == null ? null : Client.BytesField.newBuilder().setValue(ByteString.copyFrom(value)).build();
    }
}
