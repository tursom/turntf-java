package io.github.tursom.turntf.java;

/**
 * 附件记录，表示用户之间或用户与实体之间的关联关系。
 * <p>
 * 附件是 turntf 中的通用关联机制，可用于多种场景：
 * <ul>
 *   <li>频道订阅（{@link AttachmentType#CHANNEL_SUBSCRIPTION}）</li>
 *   <li>用户黑名单（{@link AttachmentType#USER_BLACKLIST}）</li>
 *   <li>用户备注等自定义关联</li>
 * </ul>
 * <p>
 * 该类为不可变记录，包含关联的双方、类型、配置数据以及时间信息。
 *
 * @param owner          附件的所有者用户引用
 * @param subject        附件关联的目标用户引用
 * @param attachmentType 附件的类型枚举
 * @param configJson     附件配置数据的 JSON 字节数组，存储与关联类型相关的自定义配置
 * @param attachedAt     附件创建时间的字符串表示
 * @param deletedAt      附件删除时间的字符串表示，如果为 {@code null} 或空则表示尚未删除
 * @param originNodeId   创建此附件记录的源节点标识
 */
public record Attachment(
    UserRef owner,
    UserRef subject,
    AttachmentType attachmentType,
    byte[] configJson,
    String attachedAt,
    String deletedAt,
    long originNodeId
) {
}
