package io.github.tursom.turntf.java;

/**
 * 发送消息的输入参数记录。
 * <p>
 * 封装了发送消息操作所需的目标用户和消息体。消息体以字节数组形式传递，
 * 上层应用可自行定义序列化协议（如 JSON、Protobuf 等）。
 *
 * @param target 消息的目标用户引用
 * @param body   消息体的原始字节数据
 */
public record SendMessageInput(UserRef target, byte[] body) {
}
