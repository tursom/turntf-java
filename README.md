# turntf-java

`turntf-java` 是 turntf 的 Java SDK，面向需要在 JVM 侧接入 turntf 服务的业务代码、后台任务和测试工具。它封装了两类能力：

- 阻塞式 HTTP JSON 管理与查询客户端 `TurntfHttpClient`
- 基于 WebSocket + Protobuf + `CompletableFuture` 的实时客户端 `TurntfClient`

当前 Java SDK 已覆盖这些核心语义：

- WebSocket 首帧登录
- 自动重连、重登录与 `seen_messages` 去重恢复
- `session_ref`、`resolveUserSessions()` 与按会话定向的 transient packet
- `saveMessage -> saveCursor -> ack` 的可靠性顺序
- 用户、附件、消息、事件、集群运维等 RPC

更细的协议与构建说明见：

- [实时客户端详解](docs/realtime-client.md)
- [构建、测试与 Proto 同步](docs/build-and-proto.md)

## 模块定位

在这个 monorepo 中：

- `turntf/` 是服务端与协议语义的主参考实现
- `turntf-java/` 负责把这些共享语义映射成 Java API
- `proto/client.proto` 是 Java SDK 本地使用的客户端协议定义

Java SDK 的两个入口各有侧重：

- `TurntfHttpClient` 适合脚本、后台任务、管理面和一次性查询；它是阻塞式 API，调用线程会直接等待 HTTP 响应
- `TurntfClient` 适合需要实时推送、自动重连、消息去重恢复和复用同一条连接执行 RPC 的应用；它的异步返回值统一用 `CompletableFuture`

需要注意的是，两个入口的能力并非完全对等：

- HTTP 客户端目前覆盖登录、创建用户、消息查询/发送、附件与黑名单管理、部分集群查询
- WebSocket 客户端除 HTTP 登录外，基本承载了更完整的业务 RPC，包括 `resolveUserSessions()`、`operationsStatus()`、`metrics()`、`listEvents()` 等
- 如果你需要按 `session_ref` 定向投递 transient packet，应优先使用 `TurntfClient`

## 环境要求

- JDK 21
- 本机可用的 Gradle

仓库当前没有附带 Gradle Wrapper，因此下面的命令都默认使用系统安装的 `gradle`。

## 安装与集成

### 方式一：在 monorepo 或多模块 Gradle 工程中直接引用源码

如果你的工程和 `turntf-java/` 一起开发，最省心的方式是直接把它作为复合构建或子模块引入。

示例：

```kotlin
// settings.gradle.kts
includeBuild("../turntf-java")
```

然后在业务模块中依赖：

```kotlin
dependencies {
    implementation("io.github.tursom:turntf-java:0.1.0")
}
```

### 方式二：发布到本地 Maven 仓库

如果你希望在独立工程中消费 SDK，可以先在 `turntf-java/` 目录执行：

```bash
gradle publishToMavenLocal
```

之后在业务工程里声明依赖：

```kotlin
dependencies {
    implementation("io.github.tursom:turntf-java:0.1.0")
}
```

当前构建脚本启用了 `maven-publish`，但仓库内没有预配置远端发布仓库；外部发布策略通常由上层工程或 CI 决定。

## 常用构建命令

```bash
gradle clean test
gradle jar
gradle generateProto
gradle publishToMavenLocal
```

命令与生成产物的细节见 [构建、测试与 Proto 同步](docs/build-and-proto.md)。

## 快速开始

### 阻塞式 HTTP API

`TurntfHttpClient` 适合简单管理操作。它会帮你处理：

- `Authorization: Bearer <token>` 注入
- `byte[]` 和 HTTP JSON 中 base64 字段的转换
- 附件 `config_json` 的嵌入式 JSON 编码
- `PasswordInput.plain(...)` 的客户端 bcrypt 哈希

```java
import io.github.tursom.turntf.java.CreateUserRequest;
import io.github.tursom.turntf.java.PasswordInput;
import io.github.tursom.turntf.java.TurntfHttpClient;
import io.github.tursom.turntf.java.UserRef;

import java.nio.charset.StandardCharsets;

TurntfHttpClient http = new TurntfHttpClient("http://127.0.0.1:8080");

String adminToken = http.login(4096, 1, "root");

var alice = http.createUser(
    adminToken,
    new CreateUserRequest(
        "alice",
        PasswordInput.plain("alice-password"),
        "{\"tier\":\"gold\"}".getBytes(StandardCharsets.UTF_8),
        "user"
    )
);

var inbox = http.listMessages(adminToken, new UserRef(alice.nodeId(), alice.userId()), 20);
var created = http.postMessage(adminToken, new UserRef(alice.nodeId(), alice.userId()), "hello".getBytes(StandardCharsets.UTF_8));
```

### 基于 `CompletableFuture` 的实时客户端

`TurntfClient` 负责管理 WebSocket 生命周期、登录帧、请求 ID 匹配、定时 ping、消息落盘与自动 ack。

```java
import io.github.tursom.turntf.java.ClientListener;
import io.github.tursom.turntf.java.Config;
import io.github.tursom.turntf.java.Credentials;
import io.github.tursom.turntf.java.LoginInfo;
import io.github.tursom.turntf.java.MemoryCursorStore;
import io.github.tursom.turntf.java.Message;
import io.github.tursom.turntf.java.NopClientListener;
import io.github.tursom.turntf.java.PasswordInput;
import io.github.tursom.turntf.java.SendMessageInput;
import io.github.tursom.turntf.java.TurntfClient;
import io.github.tursom.turntf.java.UserRef;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

ClientListener listener = new NopClientListener() {
    @Override
    public void onLogin(LoginInfo info) {
        System.out.printf(
            "login ok: protocol=%s session=%d/%s%n",
            info.protocolVersion(),
            info.sessionRef().servingNodeId(),
            info.sessionRef().sessionId()
        );
    }

    @Override
    public void onMessage(Message message) {
        System.out.printf(
            "message: recipient=%d:%d seq=%d body=%s%n",
            message.recipient().nodeId(),
            message.recipient().userId(),
            message.seq(),
            new String(message.body(), StandardCharsets.UTF_8)
        );
    }
};

TurntfClient client = new TurntfClient(new Config(
    "http://127.0.0.1:8080",
    new Credentials(4096, 1025, PasswordInput.plain("alice-password")),
    new MemoryCursorStore(),
    listener,
    null,
    true,
    Duration.ofSeconds(1),
    Duration.ofSeconds(30),
    Duration.ofSeconds(30),
    Duration.ofSeconds(10),
    true,
    false,
    false
));

client.connect().join();

var sent = client.sendMessage(
    new SendMessageInput(new UserRef(4096, 1025), "hello".getBytes(StandardCharsets.UTF_8))
).join();

System.out.println(sent.seq());
client.close();
```

## 阻塞式 HTTP API 说明

`TurntfHttpClient` 当前提供这些能力：

- `login()` / `loginWithPassword()`
- `createUser()` / `createChannel()`
- `createSubscription()`
- `listMessages()` / `postMessage()`
- `postPacket()`
- `upsertAttachment()` / `deleteAttachment()` / `listAttachments()`
- `blockUser()` / `unblockUser()` / `listBlockedUsers()`
- `listClusterNodes()` / `listNodeLoggedInUsers()`

使用时有几个容易踩坑的点：

- `login()` 和 `PasswordInput.plain(...)` 会在客户端把明文密码转成 bcrypt；如果你已经拿到 bcrypt 结果，改用 `PasswordInput.hashed(...)`
- `postMessage()` 和 `postPacket()` 都接受 `byte[] body`，HTTP 侧的 base64 编码由 SDK 处理
- `upsertAttachment()` 的 `configJson` 必须是合法 JSON 字节串，SDK 会把它嵌入外层请求体，而不是当作 base64 传输
- `postPacket()` 只能发一般的 transient packet，不支持 `target_session`
- 出现非预期 HTTP 状态码时，SDK 会抛出 `ProtocolError`，并尽量带上服务端响应体，便于定位

如果你需要 `resolveUserSessions()`、`metrics()`、`operationsStatus()` 或复用一条连接执行多类 RPC，应切换到 `TurntfClient`。

## 实时客户端说明

`TurntfClient` 的公共 API 可以分成几组：

- 生命周期：`connect()`、`close()`、`currentLogin()`、`ping()`
- 消息与 packet：`sendMessage()`、`postMessage()`、`sendPacket()`、`sendPacketToSession()`
- 用户管理：`createUser()`、`createChannel()`、`getUser()`、`updateUser()`、`deleteUser()`
- 附件与关系：`upsertAttachment()`、`deleteAttachment()`、`listAttachments()`、`subscribeChannel()`、`unsubscribeChannel()`、`blockUser()`、`unblockUser()`
- 查询与运维：`listMessages()`、`listEvents()`、`listClusterNodes()`、`listNodeLoggedInUsers()`、`resolveUserSessions()`、`operationsStatus()`、`metrics()`
- HTTP 透传：`http()`、`login()`、`loginWithPassword()`

几个关键语义：

- `connect()` 只等待“第一个成功完成认证的会话”；之后的断线重连由内部线程自动处理，不需要再次调用 `connect()`
- 每次重连前都会从 `CursorStore.loadSeenMessages()` 快照本地游标，并放入新的 `LoginRequest.seen_messages`
- 登录成功后返回的 `LoginInfo` 包含 `protocolVersion` 和 `sessionRef`
- 每个 in-flight RPC 都绑定当前 WebSocket 会话；一旦断线，该连接上的挂起请求会立即失败，而不是一直等到超时
- `sendMessage()` 返回的是服务端持久化后的 `Message`；SDK 会在 future 完成前先把它写入本地 `CursorStore`

详见 [实时客户端详解](docs/realtime-client.md)。

## `Config` / `Credentials` / `CursorStore` / `ClientListener`

### `Config`

`Config` 是实时客户端的运行时配置：

- `baseUrl`
  传 `http://host:port`、`https://host:port`、`ws://...` 或 `wss://...` 都可以；SDK 会自动映射到 `/ws/client`，若 `realtimeStream = true` 则映射到 `/ws/realtime`
- `credentials`
  WebSocket 首帧登录使用的 `(node_id, user_id, password)`
- `cursorStore`
  本地消息与游标持久化接口；为空时会退回 `MemoryCursorStore`
- `listener`
  实时事件回调；为空时会退回 `NopClientListener`
- `httpClient`
  复用的 OkHttpClient；为空时 SDK 自建
- `reconnect`
  是否自动重连
- `initialReconnectDelay` / `maxReconnectDelay`
  指数退避范围
- `pingInterval`
  应用层 ping 周期
- `requestTimeout`
  单个 RPC 超时
- `ackMessages`
  是否在本地持久化之后自动发送 `AckMessage`
- `transientOnly`
  登录时是否把 `LoginRequest.transient_only` 置为 `true`
- `realtimeStream`
  是否改连 `/ws/realtime`

### `Credentials`

`Credentials` 是一个简单 record：

```java
new Credentials(nodeId, userId, PasswordInput.plain("alice-password"))
```

密码建议：

- 开发环境可直接用 `PasswordInput.plain(...)`
- 如果上层已经统一完成 bcrypt，可以改用 `PasswordInput.hashed(...)`，避免重复哈希

### `CursorStore`

`CursorStore` 是 Java SDK 与业务持久层的接缝：

```java
public interface CursorStore {
    List<MessageCursor> loadSeenMessages();
    void saveMessage(Message message);
    void saveCursor(MessageCursor cursor);
}
```

它的约束非常重要：

- `loadSeenMessages()` 返回值会被原样写入下一次登录的 `seen_messages`
- 返回顺序应稳定，且不要在业务尚未准备好重放前过早丢弃游标
- `saveMessage()` 必须先于 `saveCursor()` 完成；一旦游标持久化，下一次重连时服务端就会据此跳过重放
- `MemoryCursorStore` 只适合 demo、测试和短生命周期进程，不适合真实持久化

### `ClientListener`

`ClientListener` 提供这些回调：

- `onLogin(LoginInfo info)`
- `onMessage(Message message)`
- `onPacket(Packet packet)`
- `onError(Throwable error)`
- `onDisconnect(Throwable error)`

回调顺序上最关键的一点是：

- `onMessage()` 只有在 SDK 完成 `saveMessage -> saveCursor`，并且在启用自动 ack 时已经尝试发送 `AckMessage` 之后才会触发

因此，业务在 `onMessage()` 里看到的消息，已经经过 SDK 侧的协议处理与本地持久化尝试。

## 自动重连、重登录与 `session_ref`

实时客户端的重连语义如下：

- 初次 `connect()` 成功后，SDK 会启动固定周期的 `ping()`
- 一旦连接断开，SDK 会清空当前认证态、失败当前连接上的 pending RPC，并触发 `onDisconnect()`
- 如果 `Config.reconnect = true` 且错误不是 `unauthorized`，SDK 会按指数退避自动重连并重新登录
- 新的登录帧会带上 `CursorStore.loadSeenMessages()` 的快照，因此服务端可以跳过已持久化消息

`session_ref` 则用于标识“这次登录对应的在线连接”：

- 登录成功后，可从 `LoginInfo.sessionRef()` 读取
- `currentLogin()` 返回的也是当前已认证会话快照
- 如果要做会话定向的 transient packet，先调用 `resolveUserSessions()` 拿到对端在线 session，再用 `sendPacketToSession()`
- 对于可选的 `target_session` 字段，Java SDK 使用零值 `SessionRef(0, "")` 表示“线缆上没有这个字段”，可通过 `isZero()` / `valid()` 判断

## 消息可靠性与 `saveMessage -> saveCursor -> ack`

Java SDK 的可靠性设计和服务端共享协议保持一致：

1. 收到 `MessagePushed`
2. 先调用 `CursorStore.saveMessage(message)`
3. 再调用 `CursorStore.saveCursor(message.cursor())`
4. 如果 `ackMessages = true`，再发送 `AckMessage`
5. 最后触发 `ClientListener.onMessage(message)`

额外注意：

- `AckMessage` 只是连接内的去重提示，不是数据库级确认
- 可靠重连真正依赖的是下一次登录时重新上报 `seen_messages`
- transient packet 没有 `(node_id, seq)` 游标，不参与 `saveCursor()`、`seen_messages` 或 `AckMessage`
- `sendMessage()` 收到 `send_message_response.message` 后，也会按同样的持久化顺序写入本地 store，再完成返回的 future

## 错误模型

Java SDK 的错误主要分四层：

- 参数校验错误：通常是 `IllegalArgumentException`，例如空密码、非法 `UserRef`、非法 `DeliveryMode`
- 网络与传输错误：`ConnectionError`
- 协议不匹配：`ProtocolError`
- 服务端业务错误：`ServerError`

实时 API 还有两点需要特别留意：

- `CompletableFuture.join()` 会把异常包成 `CompletionException`，业务通常要看 `getCause()`
- 连接关闭或尚未完成认证时，SDK 会让相关调用以 `IllegalStateException` 失败

更完整的异常传播路径和排障建议见 [实时客户端详解](docs/realtime-client.md)。

## 测试与 Proto

Java SDK 目前有两组核心单测：

- [`TurntfHttpClientTest`](src/test/java/io/github/tursom/turntf/java/TurntfHttpClientTest.java)
  验证 HTTP 登录、bcrypt 密码编码、`body` base64、附件/消息 JSON 形状
- [`TurntfClientTest`](src/test/java/io/github/tursom/turntf/java/TurntfClientTest.java)
  验证实时登录、自动 ack、持久消息发送、ping/pong 与监听器回调

Proto 相关约束：

- 本地协议源文件是 [`proto/client.proto`](proto/client.proto)
- Gradle protobuf 插件会把生成代码写到 `build/generated/sources/proto/main/java/notifier/client/v1/Client.java`
- `build/` 已被 `.gitignore` 忽略，因此生成代码属于构建产物，不是手工维护文件
- 如果你改了 `proto/client.proto`，要同时检查 `TurntfClient`、`TurntfHttpClient`、`internal/ProtoAdapters`、测试和本文档是否都还同步

更细的构建与同步流程见 [构建、测试与 Proto 同步](docs/build-and-proto.md)。
