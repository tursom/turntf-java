# 构建、测试与 Proto 同步

本文说明 `turntf-java/` 的日常开发命令、测试覆盖范围，以及 `proto/client.proto` 与生成代码的同步要求。

## 构建前提

- JDK 21
- 本机安装可用的 `gradle`

当前目录没有 Gradle Wrapper，因此本文默认直接执行 `gradle`。

## 构建脚本摘要

[`build.gradle.kts`](../build.gradle.kts) 当前有这些关键信息：

- 插件：
  - `java-library`
  - `maven-publish`
  - `com.google.protobuf`
- `group = "io.github.tursom"`
- `version = "0.1.0"`
- Java toolchain：21
- 主要依赖：
  - `okhttp`
  - `jbcrypt`
  - `jackson-databind`
  - `protobuf-java`
- 测试框架：
  - JUnit 5
  - MockWebServer

## 常用命令

### 执行单元测试

```bash
gradle clean test
```

### 仅编译与打包

```bash
gradle jar
```

### 生成源码包与 Javadoc 包

```bash
gradle sourcesJar javadocJar
```

### 发布到本地 Maven 仓库

```bash
gradle publishToMavenLocal
```

### 重新生成 protobuf Java 代码

```bash
gradle generateProto
```

如果你同时改了 Java 源码和本地 proto，通常最稳妥的流程是：

```bash
gradle clean generateProto test
```

## 测试覆盖说明

Java SDK 当前最关键的两组测试是：

### `TurntfHttpClientTest`

文件：[`src/test/java/io/github/tursom/turntf/java/TurntfHttpClientTest.java`](../src/test/java/io/github/tursom/turntf/java/TurntfHttpClientTest.java)

它主要验证：

- `login()` 会把明文密码转成 bcrypt 再发送
- `createUser()` 会把 `PasswordInput.plain(...)` 编码成服务端接受的密码字段
- `postMessage()` 会把 `byte[] body` 正确编码到 HTTP JSON
- `listMessages()` 会把 HTTP 返回体中的 base64 还原成 `byte[]`
- Bearer token 注入与请求路径拼装

这组测试适合保护 HTTP 边界层的 JSON 形状与编码兼容性。

### `TurntfClientTest`

文件：[`src/test/java/io/github/tursom/turntf/java/TurntfClientTest.java`](../src/test/java/io/github/tursom/turntf/java/TurntfClientTest.java)

它主要验证：

- WebSocket 首帧登录
- `PasswordInput.plain(...)` 在登录帧中的 bcrypt 结果
- `LoginResponse` 到 `LoginInfo` 的映射
- 推送消息后的自动 ack
- `sendMessage()` / `send_message_response`
- `ping()` / `pong`
- `CursorStore` 与 `ClientListener` 的基本联动

目前这些测试已经覆盖了 Java SDK 最核心的接入路径，但仍然建议在协议演进时额外关注：

- `session_ref`
- `resolveUserSessions()`
- `sendPacketToSession()`
- `transientOnly`
- `realtimeStream`
- `operationsStatus()` / `metrics()`

如果这些共享语义发生变化，最好同步补测试。

## Proto 目录与生成产物

本地客户端协议源文件：

- [`proto/client.proto`](../proto/client.proto)

`build.gradle.kts` 已显式把它注册进 `main` source set：

```kotlin
sourceSets {
    main {
        proto {
            srcDir("proto")
            include("client.proto")
        }
    }
}
```

当前 `protoc` 版本来自：

```kotlin
protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.29.3"
    }
}
```

生成后的 Java 文件位于：

- `build/generated/sources/proto/main/java/notifier/client/v1/Client.java`

由于 `.gitignore` 已忽略 `build/`，这些生成文件属于构建产物，而不是手工维护文件。

## 什么时候需要重新生成 Proto

以下任一情况发生时，都应该执行 `gradle generateProto`：

- 修改了 `turntf-java/proto/client.proto`
- 从服务端同步了新的 `client.proto`
- 调整了 protobuf 插件版本或 `protoc` 版本

如果只是改文档，不需要重新生成。

## Proto 同步检查清单

`client.proto` 是跨 SDK 共享约束的一部分。修改它时，至少检查下面这些位置是否同步：

- Java 公开模型是否仍然匹配 wire schema
  - `SessionRef`
  - `MessageCursor`
  - `Message`
  - `Packet`
  - `ResolvedUserSessions`
- 映射层是否仍然正确
  - [`src/main/java/io/github/tursom/turntf/java/internal/ProtoAdapters.java`](../src/main/java/io/github/tursom/turntf/java/internal/ProtoAdapters.java)
- 实时客户端是否仍然按协议约束发包/收包
  - [`src/main/java/io/github/tursom/turntf/java/TurntfClient.java`](../src/main/java/io/github/tursom/turntf/java/TurntfClient.java)
- HTTP 边界是否仍然与共享语义兼容
  - [`src/main/java/io/github/tursom/turntf/java/TurntfHttpClient.java`](../src/main/java/io/github/tursom/turntf/java/TurntfHttpClient.java)
- 单元测试是否需要扩展
  - `TurntfHttpClientTest`
  - `TurntfClientTest`
- 文档是否仍然准确
  - [`README.md`](../README.md)
  - [`docs/realtime-client.md`](realtime-client.md)

## JVM SDK 同步特别注意

仓库规范要求：

- `turntf-java/proto/client.proto` 和 `turntf-kt/proto/client.proto` 是各自模块的本地协议定义
- 修改任一 JVM SDK 的 proto 时，要保证本模块生成结果与实现代码同步

因此，当 Java SDK 侧的 `client.proto` 变更涉及共享协议时，主线程通常还需要同步确认：

- Kotlin SDK 的本地 `proto/client.proto` 是否也需要更新
- 两边对 `session_ref`、`target_session`、`seen_messages`、`AckMessage` 的解释是否一致
- 文档中关于自动重连、pending RPC 和可靠性顺序的描述是否需要双边同步

## 推荐开发流程

如果本次修改同时涉及实现、测试和 proto，可以按这个顺序推进：

1. 先改 `proto/client.proto` 或先确认服务端协议已变更
2. 执行 `gradle generateProto`
3. 更新 `TurntfClient` / `TurntfHttpClient` / `ProtoAdapters`
4. 更新或新增测试
5. 执行 `gradle test`
6. 最后更新 README 与专题文档

如果只是文档补充，则至少应先阅读：

- `TurntfClient`
- `TurntfHttpClient`
- `CursorStore`
- `ClientListener`
- 现有测试

这样文档才能跟当前实现保持一致。
