# turntf-java

`turntf-java` 是 turntf 的 Java SDK，面向需要在 JVM 侧接入 turntf 服务的业务代码、后台任务和测试工具。它封装了两类能力：

- **TurntfHttpClient**：阻塞式 HTTP JSON 管理与查询客户端，适用于脚本、后台任务和管理面
- **TurntfClient**：基于 WebSocket + Protobuf + `CompletableFuture` 的实时客户端，适用于需要实时推送和自动重连的应用

当前 Java SDK 已覆盖这些核心语义：

- WebSocket 首帧登录
- 自动重连、重登录与 `seen_messages` 去重恢复
- `session_ref`、`resolveUserSessions()` 与按会话定向的 transient packet
- `saveMessage -> saveCursor -> ack` 的可靠性顺序
- 用户、附件、消息、事件、元数据、集群运维等 RPC

## 安装与集成

### 方式一：在 monorepo 或多模块 Gradle 工程中直接引用源码

如果你的工程和 `turntf-java/` 一起开发，最省心的方式是直接把它作为复合构建或子模块引入。

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

## 快速开始

### TurntfHttpClient（阻塞式 HTTP API）

`TurntfHttpClient` 适用于简单管理操作。它会自动处理：

- `Authorization: Bearer <token>` 注入
- `byte[]` 与 HTTP JSON 中 base64 字段的转换
- 附件 `config_json` 的嵌入式 JSON 编码
- `PasswordInput.plain(...)` 的客户端 bcrypt 哈希

```java
import io.github.tursom.turntf.java.CreateUserRequest;
import io.github.tursom.turntf.java.ListUsersFilter;
import io.github.tursom.turntf.java.PasswordInput;
import io.github.tursom.turntf.java.TurntfHttpClient;
import io.github.tursom.turntf.java.UserRef;

import java.nio.charset.StandardCharsets;

// 创建客户端（可选的第二个参数为 OkHttpClient）
TurntfHttpClient http = new TurntfHttpClient("http://127.0.0.1:8080");

// 登录：支持旧版 (nodeId, userId) 和新版 loginName 两种方式
String adminToken = http.login(4096, 1, "root");
String aliceToken = http.login("alice.login", "alice-password");

// 创建用户
var alice = http.createUser(adminToken, new CreateUserRequest(
    "alice",
    "alice.login",
    PasswordInput.plain("alice-password"),
    "{\"tier\":\"gold\"}".getBytes(StandardCharsets.UTF_8),
    "user"
));

// 查询用户消息列表
var inbox = http.listMessages(adminToken, new UserRef(alice.nodeId(), alice.userId()), 20);

// 查询当前用户可通讯的活跃用户，支持 name / uid 过滤
var contacts = http.listUsers(aliceToken, new ListUsersFilter(
    "alice",
    new UserRef(alice.nodeId(), alice.userId())
));

// 发送消息（body 为原始字节）
var created = http.postMessage(adminToken, new UserRef(alice.nodeId(), alice.userId()),
    "hello".getBytes(StandardCharsets.UTF_8));
```

### TurntfClient（基于 CompletableFuture 的实时客户端）

`TurntfClient` 负责管理 WebSocket 生命周期、登录帧、请求 ID 匹配、定时 ping、消息落盘与自动 ack。所有异步操作均返回 `CompletableFuture`。

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

// 定义事件监听器
ClientListener listener = new NopClientListener() {
    @Override
    public void onLogin(LoginInfo info) {
        System.out.printf("登录成功: protocol=%s session=%d/%s%n",
            info.protocolVersion(),
            info.sessionRef().servingNodeId(),
            info.sessionRef().sessionId());
    }

    @Override
    public void onMessage(Message message) {
        System.out.printf("收到消息: recipient=%d:%d seq=%d body=%s%n",
            message.recipient().nodeId(),
            message.recipient().userId(),
            message.seq(),
            new String(message.body(), StandardCharsets.UTF_8));
    }
};

// 配置实时客户端
TurntfClient client = new TurntfClient(new Config(
    "http://127.0.0.1:8080",
    new Credentials(4096, 1025, PasswordInput.plain("alice-password")),
    new MemoryCursorStore(),
    listener,
    null,            // 复用 OkHttpClient（null 则自建）
    true,            // 启用自动重连
    Duration.ofSeconds(1),   // 初始重连延迟
    Duration.ofSeconds(30),  // 最大重连延迟
    Duration.ofSeconds(30),  // ping 间隔
    Duration.ofSeconds(10),  // RPC 超时
    true,            // 自动 ack
    false,           // transientOnly
    false            // realtimeStream
));

// 连接（等待首次认证完成）
client.connect().join();

// 发送持久消息
var sent = client.sendMessage(new SendMessageInput(
    new UserRef(4096, 1025),
    "hello".getBytes(StandardCharsets.UTF_8)
)).join();

System.out.println("消息发送成功，seq=" + sent.seq());

// 关闭客户端
client.close();
```

`Config` 也提供了一个精简构造器，适用于快速原型：

```java
Config config = new Config(
    "http://127.0.0.1:8080",
    new Credentials("alice.login", PasswordInput.plain("alice-password"))
);
// 精简构造器默认：重连开启、ping 间隔 30s、RPC 超时 10s、自动 ack 开启
```

## API 概览

### TurntfHttpClient（阻塞式）

| 分类 | 方法 | 说明 |
|------|------|------|
| 登录 | `login(long, long, String)` / `login(String, String)` | 旧版 nodeId+userId 或新版 loginName 登录 |
| 登录 | `loginWithPassword(long, long, PasswordInput)` / `loginWithPassword(String, PasswordInput)` | 支持传入预哈希密码 |
| 用户管理 | `createUser(String, CreateUserRequest)` / `createChannel(String, CreateUserRequest)` | 创建用户/频道 |
| 用户管理 | `listUsers(String, ...)` / `listUsers(String, ListUsersFilter)` | 查询当前用户可通讯的活跃用户，支持 `name` / `uid` 过滤 |
| 订阅 | `createSubscription(String, UserRef, UserRef)` | 创建用户-频道订阅 |
| 消息 | `listMessages(String, UserRef, int)` / `postMessage(String, UserRef, byte[])` | 查询/发送消息 |
| 数据包 | `postPacket(String, long, UserRef, byte[], DeliveryMode)` | 发送瞬态中继包 |
| 附件 | `upsertAttachment(...)` / `deleteAttachment(...)` / `listAttachments(...)` | 附件 CRUD |
| 黑名单 | `blockUser(...)` / `unblockUser(...)` / `listBlockedUsers(...)` | 用户黑名单管理 |
| 元数据 | `getUserMetadata(...)` / `upsertUserMetadata(...)` / `deleteUserMetadata(...)` / `scanUserMetadata(...)` | 用户私有元数据 |
| 集群 | `listClusterNodes(String)` / `listNodeLoggedInUsers(String, long)` | 集群节点与在线用户查询 |

### TurntfClient（实时异步）

| 分类 | 方法 | 返回类型 |
|------|------|----------|
| 生命周期 | `connect()` / `close()` / `currentLogin()` / `ping()` | `CompletableFuture<Void>` / `Optional<LoginInfo>` |
| 消息收发 | `sendMessage(SendMessageInput)` / `postMessage(SendMessageInput)` | `CompletableFuture<Message>` |
| 数据包 | `sendPacket(SendPacketInput)` / `sendPacketToSession(...)` | `CompletableFuture<RelayAccepted>` |
| 用户管理 | `createUser(CreateUserRequest)` / `createChannel(CreateUserRequest)` / `getUser(UserRef)` / `updateUser(UserRef, UpdateUserRequest)` / `deleteUser(UserRef)` / `listUsers(...)` | `CompletableFuture<User>` / `CompletableFuture<DeleteUserResult>` / `CompletableFuture<List<User>>` |
| 频道订阅 | `subscribeChannel(...)` / `unsubscribeChannel(...)` / `listSubscriptions(...)` / `createSubscription(...)` | `CompletableFuture<Subscription>` / `CompletableFuture<Void>` |
| 附件 | `upsertAttachment(...)` / `deleteAttachment(...)` / `listAttachments(...)` | `CompletableFuture<Attachment>` / `CompletableFuture<List<Attachment>>` |
| 黑名单 | `blockUser(...)` / `unblockUser(...)` / `listBlockedUsers(...)` | `CompletableFuture<BlacklistEntry>` / `CompletableFuture<List<BlacklistEntry>>` |
| 元数据 | `getUserMetadata(...)` / `upsertUserMetadata(...)` / `deleteUserMetadata(...)` / `scanUserMetadata(...)` | `CompletableFuture<UserMetadata>` / `CompletableFuture<UserMetadataScanResult>` |
| 消息查询 | `listMessages(UserRef, int)` | `CompletableFuture<List<Message>>` |
| 事件 | `listEvents(long, int)` | `CompletableFuture<List<Event>>` |
| 会话解析 | `resolveUserSessions(UserRef)` | `CompletableFuture<ResolvedUserSessions>` |
| 运维 | `listClusterNodes()` / `listNodeLoggedInUsers(long)` / `operationsStatus()` / `metrics()` | 各类 `CompletableFuture` |
| HTTP 透传 | `http()` | 返回关联的 `TurntfHttpClient` |

## 选型建议

| 场景 | 推荐客户端 | 原因 |
|------|-----------|------|
| 管理脚本、后台任务、一次性查询 | TurntfHttpClient | 阻塞式 API，调用简单，无需管理连接生命周期 |
| 需要实时推送的应用 | TurntfClient | WebSocket 长连接，自动重连，消息去重恢复 |
| 需要 `resolveUserSessions()` 等高级 RPC | TurntfClient | HTTP 客户端不覆盖这些操作 |
| 多类 RPC 复用一个连接 | TurntfClient | 同一条 WebSocket 连接可执行所有 RPC |
| 会话定向的 transient packet | TurntfClient | `sendPacketToSession()` 仅在 WebSocket 路径可用 |

需要注意两个入口的能力并非完全对等：

- HTTP 客户端覆盖登录、创建用户、消息查询/发送、附件与黑名单管理、部分集群查询和元数据管理
- WebSocket 客户端承载了更完整的业务 RPC，包括 `resolveUserSessions()`、`operationsStatus()`、`metrics()`、`listEvents()` 等
- 两条路径现在都支持“可通讯用户列表”查询：HTTP 使用 `name` + `uid=node_id:user_id` 查询串，WebSocket `list_users` 使用 `name` + `uid: UserRef`
- 如果你需要按 `session_ref` 定向投递 transient packet，应优先使用 `TurntfClient`

## 文档导航

- [实时客户端详解](docs/realtime-client.md) -- TurntfClient 生命周期、重连语义、消息可靠性
- [HTTP 客户端详解](docs/http-client.md) -- TurntfHttpClient 使用细节与注意事项
- [构建、测试与 Proto 同步](docs/build-and-proto.md) -- 构建命令、Proto 生成与同步流程
- [开发指南](docs/development.md) -- 开发环境配置与贡献指南
- [SDK 集成指南](docs/sdk-guide.md) -- 面向 SDK 使用者的集成指引

### 核心配置理解

实时客户端的运行时配置通过 `Config` record 定义。关键的配置项包括：

- **`baseUrl`**：服务器地址，支持 `http://`、`https://`、`ws://`、`wss://`；SDK 自动映射到 `/ws/client` 或 `/ws/realtime`
- **`credentials`**：登录凭据，支持 `(nodeId, userId, password)` 或 `(loginName, password)` 两种方式
- **`cursorStore`**：本地游标持久化接口，`MemoryCursorStore` 适合 demo 和测试
- **`listener`**：实时事件回调，通过 `NopClientListener` 选择性实现感兴趣的方法
- **`reconnect` / `initialReconnectDelay` / `maxReconnectDelay`**：自动重连与指数退避策略
- **`pingInterval` / `requestTimeout`**：应用层 ping 周期与单次 RPC 超时

`Credentials` 支持两种互斥构造方式：

```java
new Credentials(nodeId, userId, PasswordInput.plain("alice-password"))
new Credentials("alice.login", PasswordInput.plain("alice-password"))
```

`CursorStore` 接口定义了 SDK 与持久化层的契约：

```java
public interface CursorStore {
    List<MessageCursor> loadSeenMessages();  // 重连时上报已确认游标
    void saveMessage(Message message);       // 先保存消息体
    void saveCursor(MessageCursor cursor);   // 再保存游标
}
```

`ClientListener` 提供以下回调：

- `onLogin(LoginInfo info)` -- 登录成功
- `onMessage(Message message)` -- 收到新消息（已持久化并 ack）
- `onPacket(Packet packet)` -- 收到瞬态数据包
- `onError(Throwable error)` -- 非致命错误
- `onDisconnect(Throwable error)` -- 连接断开

### 自动重连、重登录与 session_ref

- 初次 `connect()` 成功后，SDK 自动启动定时 ping
- 断开时清空认证态、失败 pending RPC、触发 `onDisconnect()`；若 `reconnect=true` 且非鉴权错误，则按指数退避自动重连
- 重连帧携带 `CursorStore.loadSeenMessages()` 快照，服务端跳过已持久化消息
- `LoginInfo.sessionRef()` 标识当前在线会话，`sendPacketToSession()` 可定向投递

### 消息可靠性：saveMessage -> saveCursor -> ack

1. 收到 `MessagePushed`
2. 调用 `CursorStore.saveMessage(message)` -- 持久化消息体
3. 调用 `CursorStore.saveCursor(message.cursor())` -- 持久化游标
4. 若 `ackMessages=true`，发送 `AckMessage`
5. 触发 `ClientListener.onMessage(message)`

注意：`AckMessage` 仅为连接内去重提示；真正的可靠恢复依赖重连时上报的 `seen_messages`。

### 错误模型

| 层级 | 异常类型 | 说明 |
|------|---------|------|
| 参数校验 | `IllegalArgumentException` | 空密码、非法 `UserRef`、非法 `DeliveryMode` |
| 网络传输 | `ConnectionError` | I/O 异常、连接失败 |
| 协议不匹配 | `ProtocolError` | 非预期 HTTP 状态码、无效 Protobuf 帧 |
| 业务错误 | `ServerError` | 服务端返回的业务错误（含 `unauthorized()` 判断） |

实时 API 需注意：`CompletableFuture.join()` 将异常包装为 `CompletionException`，业务应检查 `getCause()`。连接未就绪或已关闭时，SDK 抛出 `IllegalStateException`。

## 环境要求

- JDK 21
- 本机可用的 Gradle

仓库当前没有附带 Gradle Wrapper，因此下面的命令都默认使用系统安装的 `gradle`。

## 构建与测试

```bash
gradle clean test       # 运行全部测试
gradle jar              # 构建 JAR
gradle generateProto    # 从 proto/client.proto 生成 Java 代码
gradle publishToMavenLocal  # 发布到本地 Maven 仓库
```

当前有两组核心单测：

- [`TurntfHttpClientTest`](src/test/java/io/github/tursom/turntf/java/TurntfHttpClientTest.java) -- 验证 HTTP 登录、bcrypt 密码编码、body base64、附件/消息 JSON 形状、元数据 CRUD
- [`TurntfClientTest`](src/test/java/io/github/tursom/turntf/java/TurntfClientTest.java) -- 验证实时登录、自动 ack、持久消息发送、ping/pong 与监听器回调

Proto 相关约束：

- 本地协议源文件是 [`proto/client.proto`](proto/client.proto)
- Gradle protobuf 插件把生成代码写到 `build/generated/sources/proto/main/java/notifier/client/v1/Client.java`
- `build/` 已被 `.gitignore` 忽略，生成代码属于构建产物
- 修改 `proto/client.proto` 后，需同步检查 `TurntfClient`、`TurntfHttpClient`、`internal/ProtoAdapters`、测试和本文档

更细的构建与同步流程见 [构建、测试与 Proto 同步](docs/build-and-proto.md)。
