# turntf-java SDK 开发指南

## 项目概览

`turntf-java` 是 turntf 分布式通知服务的 Java SDK，位于 `sdk/turntf-java/`，作为根仓库的 submodule 管理。

### 技术栈

- **语言**：Java 21
- **构建工具**：Gradle（当前未附带 Gradle Wrapper，使用系统安装的 `gradle`）
- **异步模型**：`CompletableFuture` 驱动的异步 API
- **实时通信**：OkHttp WebSocket + Protobuf
- **HTTP 客户端**：OkHttp 阻塞 HTTP
- **JSON 序列化**：Jackson
- **密码哈希**：jbcrypt
- **协议代码生成**：protobuf-java + protoc 4.29.3
- **测试框架**：JUnit 5 + MockWebServer

### 模块定位

Java SDK 封装了两类能力：

1. **阻塞式 HTTP JSON 客户端** `TurntfHttpClient`——适合脚本、后台任务、管理面和一次性查询
2. **基于 WebSocket + Protobuf 的实时客户端** `TurntfClient`——适合需要实时推送、自动重连、消息去重恢复的应用

SDK 的两个入口各有侧重，能力不完全对等。HTTP 客户端覆盖登录、创建用户、消息查询/发送、附件与黑名单管理、部分集群查询；WebSocket 客户端承载了更完整的业务 RPC。

### 可靠性设计

- 消息可靠性基于 `saveMessage -> saveCursor -> ack` 的严格顺序
- 自动重连通过指数退避和 `seen_messages` 去重实现
- `AckMessage` 仅为连接内去重提示，可靠重连依赖本地游标持久化
- transient packet 没有游标，不参与持久化流程

---

## 构建与测试命令

所有命令在 `sdk/turntf-java/` 目录下执行，使用系统安装的 `gradle`。

### 常用命令

| 命令 | 说明 |
|------|------|
| `gradle build` | 完整构建（编译 + 测试 + 打包） |
| `gradle clean test` | 清理后执行单元测试 |
| `gradle jar` | 仅编译与打包 |
| `gradle sourcesJar` | 生成源码包 |
| `gradle javadocJar` | 生成 Javadoc 包 |
| `gradle generateProto` | 重新生成 protobuf Java 代码 |
| `gradle clean generateProto test` | Proto 变更后的完整校验流程 |
| `gradle publishToMavenLocal` | 发布到本地 Maven 仓库 |

### 测试命令详解

```bash
# 执行全部单元测试
gradle test

# 仅运行 HTTP 客户端测试
gradle test --tests "*TurntfHttpClientTest*"

# 仅运行实时客户端测试
gradle test --tests "*TurntfClientTest*"

# 带详细输出的测试
gradle test --info
```

### 并行构建

```bash
gradle build --parallel
```

---

## Proto 生成说明

### 源文件位置

本地协议定义位于 `proto/client.proto`，包名为 `notifier.client.v1`。

### 生成配置

`build.gradle.kts` 中通过 `com.google.protobuf` 插件配置了 protoc：

```kotlin
protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.29.3"
    }
}
```

源文件通过 `sourceSets` 注册：

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

### 生成产物

- 生成路径：`build/generated/sources/proto/main/java/notifier/client/v1/Client.java`
- `build/` 已被 `.gitignore` 忽略，生成代码属于构建产物，不作为手工维护文件

### 重新生成的触发条件

- 修改了 `turntf-java/proto/client.proto`
- 从服务端同步了新的 `client.proto`
- 调整了 protobuf 插件版本或 `protoc` 版本

### Proto 同步检查清单

修改 `client.proto` 时需同步检查以下位置：

1. Java 公开模型是否匹配 wire schema（`SessionRef`、`MessageCursor`、`Message`、`Packet`、`ResolvedUserSessions`）
2. 映射层 `internal/ProtoAdapters.java` 是否正确转换
3. 实时客户端 `TurntfClient.java` 发包/收包逻辑是否按协议约束
4. HTTP 边界 `TurntfHttpClient.java` 是否兼容共享语义
5. 单元测试是否需要扩展
6. 文档（README、docs/）是否准确

### JVM SDK 同步注意事项

- `turntf-java/proto/client.proto` 和 `turntf-kt/proto/client.proto` 是各自模块的本地协议定义
- 修改任一 JVM SDK 的 proto 时，保证本模块生成结果与实现代码同步
- 涉及共享协议变更时同步确认 Kotlin SDK 是否需要更新

---

## 包结构

```
io.github.tursom.turntf.java
├── TurntfClient.java              # WebSocket 实时客户端（主入口）
├── TurntfHttpClient.java          # HTTP JSON 阻塞客户端（管理面入口）
├── Config.java                    # 实时客户端运行时配置
├── Credentials.java               # 登录身份（node_id + user_id + password）
├── PasswordInput.java             # 密码输入封装（明文/hashed）
├── ClientListener.java            # 实时事件回调接口
├── NopClientListener.java         # ClientListener 的空实现
├── CursorStore.java               # 游标持久化接口
├── MemoryCursorStore.java         # 内存游标存储（仅测试/demo）
├── LoginInfo.java                 # 登录成功信息
├── SessionRef.java                # 会话引用
├── UserRef.java                   # 用户引用
├── Message.java                   # 持久化消息模型
├── MessageCursor.java             # 消息游标
├── Packet.java                    # Transient packet 模型
├── RelayAccepted.java             # 路由接受确认
├── SendMessageInput.java          # 发送消息输入
├── SendPacketInput.java           # 发送 packet 输入
├── User.java                      # 用户模型
├── CreateUserRequest.java         # 创建用户请求
├── UpdateUserRequest.java         # 更新用户请求
├── DeleteUserResult.java          # 删除用户结果
├── Attachment.java                # 附件模型
├── AttachmentType.java            # 附件类型枚举
├── BlacklistEntry.java            # 黑名单条目
├── Subscription.java              # 频道订阅
├── Event.java                     # 事件模型
├── ClusterNode.java               # 集群节点
├── LoggedInUser.java              # 在线用户
├── ResolvedUserSessions.java      # 解析的用户会话
├── OperationsStatus.java          # 运维状态
├── UserMetadata.java              # 用户元数据
├── UserMetadataScanResult.java    # 元数据扫描结果
├── DeliveryMode.java              # 投递模式枚举
├── TurntfException.java           # 异常基类
├── ConnectionError.java           # 网络/传输错误
├── ProtocolError.java             # 协议错误
├── ServerError.java               # 服务端业务错误
└── internal/
    ├── ProtoAdapters.java         # Protobuf <-> 公开模型转换
    ├── JsonCodec.java             # HTTP JSON 编解码
    └── Validation.java            # 参数校验与 URL 工具
```

### 包组织原则

- 公开模型类在 `io.github.tursom.turntf.java` 直接对外暴露
- 内部实现细节在 `internal/` 子包中，不保证 API 稳定性
- 所有公开 API 优先使用 Javadoc 说明职责、方法语义和参数约束

---

## 核心 API 说明

### TurntfClient（实时客户端）

`TurntfClient` 是 WebSocket 优先的主入口，管理完整的实时协议生命周期。

**生命周期方法：**

| 方法 | 返回类型 | 说明 |
|------|----------|------|
| `connect()` | `CompletableFuture<Void>` | 启动 WebSocket 生命周期，完成首次认证后解决 |
| `close()` | `void` | 关闭连接，停止重连，失败所有 pending RPC |
| `currentLogin()` | `Optional<LoginInfo>` | 返回当前已认证会话快照 |
| `ping()` | `CompletableFuture<Void>` | 应用层 ping |
| `http()` | `TurntfHttpClient` | 获取关联的 HTTP 客户端 |

**消息与 packet 发送：**

| 方法 | 返回类型 | 说明 |
|------|----------|------|
| `sendMessage(SendMessageInput)` | `CompletableFuture<Message>` | 发送持久化消息 |
| `postMessage(SendMessageInput)` | `CompletableFuture<Message>` | `sendMessage` 的别名 |
| `sendPacket(SendPacketInput)` | `CompletableFuture<RelayAccepted>` | 发送 transient packet |
| `sendPacketToSession(UserRef, SessionRef, byte[], DeliveryMode)` | `CompletableFuture<RelayAccepted>` | 会话定向 packet |

**用户管理：**

| 方法 | 返回类型 | 说明 |
|------|----------|------|
| `createUser(CreateUserRequest)` | `CompletableFuture<User>` | 创建用户 |
| `createChannel(CreateUserRequest)` | `CompletableFuture<User>` | 创建频道 |
| `getUser(UserRef)` | `CompletableFuture<User>` | 获取用户信息 |
| `updateUser(UserRef, UpdateUserRequest)` | `CompletableFuture<User>` | 更新用户 |
| `deleteUser(UserRef)` | `CompletableFuture<DeleteUserResult>` | 删除用户 |

**附件与关系：**

| 方法 | 返回类型 | 说明 |
|------|----------|------|
| `upsertAttachment(UserRef, UserRef, AttachmentType, byte[])` | `CompletableFuture<Attachment>` | 创建/更新附件 |
| `deleteAttachment(UserRef, UserRef, AttachmentType)` | `CompletableFuture<Attachment>` | 删除附件 |
| `listAttachments(UserRef, AttachmentType)` | `CompletableFuture<List<Attachment>>` | 列举附件 |
| `subscribeChannel(UserRef, UserRef)` | `CompletableFuture<Subscription>` | 订阅频道 |
| `unsubscribeChannel(UserRef, UserRef)` | `CompletableFuture<Subscription>` | 取消订阅 |
| `listSubscriptions(UserRef)` | `CompletableFuture<List<Subscription>>` | 列举订阅 |
| `blockUser(UserRef, UserRef)` | `CompletableFuture<BlacklistEntry>` | 拉黑用户 |
| `unblockUser(UserRef, UserRef)` | `CompletableFuture<BlacklistEntry>` | 取消拉黑 |
| `listBlockedUsers(UserRef)` | `CompletableFuture<List<BlacklistEntry>>` | 列举黑名单 |

**查询与运维：**

| 方法 | 返回类型 | 说明 |
|------|----------|------|
| `listMessages(UserRef, int)` | `CompletableFuture<List<Message>>` | 列举消息 |
| `listEvents(long, int)` | `CompletableFuture<List<Event>>` | 列举事件 |
| `listClusterNodes()` | `CompletableFuture<List<ClusterNode>>` | 列举集群节点 |
| `listNodeLoggedInUsers(long)` | `CompletableFuture<List<LoggedInUser>>` | 列举节点在线用户 |
| `resolveUserSessions(UserRef)` | `CompletableFuture<ResolvedUserSessions>` | 解析用户在线会话 |
| `operationsStatus()` | `CompletableFuture<OperationsStatus>` | 查询运维状态 |
| `metrics()` | `CompletableFuture<String>` | 获取 metrics 文本 |

**用户元数据：**

| 方法 | 返回类型 | 说明 |
|------|----------|------|
| `getUserMetadata(UserRef, String)` | `CompletableFuture<UserMetadata>` | 读取元数据 |
| `upsertUserMetadata(UserRef, String, byte[], String)` | `CompletableFuture<UserMetadata>` | 创建/更新元数据 |
| `deleteUserMetadata(UserRef, String)` | `CompletableFuture<UserMetadata>` | 删除元数据 |
| `scanUserMetadata(UserRef, String, String, int)` | `CompletableFuture<UserMetadataScanResult>` | 扫描元数据 |

### TurntfHttpClient（HTTP 客户端）

阻塞式 HTTP 客户端，适合管理面和一次性查询。

**核心方法：**

| 方法 | 返回类型 | 说明 |
|------|----------|------|
| `login(long, long, String)` | `String` | HTTP 登录（明文密码自动 bcrypt） |
| `loginWithPassword(long, long, PasswordInput)` | `String` | HTTP 登录（使用已有 PasswordInput） |
| `createUser(String, CreateUserRequest)` | `User` | 创建用户 |
| `createChannel(String, CreateUserRequest)` | `User` | 创建频道 |
| `createSubscription(String, UserRef, UserRef)` | `void` | 创建订阅 |
| `listMessages(String, UserRef, int)` | `List<Message>` | 列举消息 |
| `postMessage(String, UserRef, byte[])` | `Message` | 发送消息 |
| `postPacket(String, long, UserRef, byte[], DeliveryMode)` | `void` | 发送 transient packet |
| `upsertAttachment(String, UserRef, UserRef, AttachmentType, byte[])` | `Attachment` | 创建/更新附件 |
| `deleteAttachment(String, UserRef, UserRef, AttachmentType)` | `Attachment` | 删除附件 |
| `listAttachments(String, UserRef, AttachmentType)` | `List<Attachment>` | 列举附件 |
| `blockUser(String, UserRef, UserRef)` | `BlacklistEntry` | 拉黑用户 |
| `unblockUser(String, UserRef, UserRef)` | `BlacklistEntry` | 取消拉黑 |
| `listBlockedUsers(String, UserRef)` | `List<BlacklistEntry>` | 列举黑名单 |
| `getUserMetadata(String, UserRef, String)` | `UserMetadata` | 读取元数据 |
| `upsertUserMetadata(String, UserRef, String, byte[], String)` | `UserMetadata` | 创建/更新元数据 |
| `deleteUserMetadata(String, UserRef, String)` | `UserMetadata` | 删除元数据 |
| `scanUserMetadata(String, UserRef, String, String, int)` | `UserMetadataScanResult` | 扫描元数据 |
| `listClusterNodes(String)` | `List<ClusterNode>` | 列举集群节点 |
| `listNodeLoggedInUsers(String, long)` | `List<LoggedInUser>` | 列举节点在线用户 |

### Config（运行时配置）

`Config` 是实时客户端的配置 record，控制所有关键行为：

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `baseUrl` | `String` | 必填 | 服务端基地址，自动映射 WebSocket 路径 |
| `credentials` | `Credentials` | 必填 | 登录身份 |
| `cursorStore` | `CursorStore` | `MemoryCursorStore` | 本地游标持久化实现 |
| `listener` | `ClientListener` | `NopClientListener` | 实时事件回调 |
| `httpClient` | `OkHttpClient` | 自建 | 复用的 OkHttp 客户端 |
| `reconnect` | `boolean` | `true` | 是否自动重连 |
| `initialReconnectDelay` | `Duration` | `1s` | 初始重连延迟 |
| `maxReconnectDelay` | `Duration` | `30s` | 最大重连延迟 |
| `pingInterval` | `Duration` | `30s` | ping 间隔 |
| `requestTimeout` | `Duration` | `10s` | RPC 超时 |
| `ackMessages` | `boolean` | `true` | 是否自动 ack |
| `transientOnly` | `boolean` | `false` | 仅接收 transient 流 |
| `realtimeStream` | `boolean` | `false` | 是否连 `/ws/realtime` |

### Credentials 与 PasswordInput

`Credentials` 是一个简单的 record，包含 `nodeId`、`userId` 和 `password`（`PasswordInput` 类型）。

`PasswordInput` 有两种构造方式：
- `PasswordInput.plain("...")`：传入明文密码，SDK 自动生成 bcrypt 哈希
- `PasswordInput.hashed("$2a$...")`：复用已存在的 bcrypt 字符串

### CursorStore（游标持久化接口）

```java
public interface CursorStore {
    List<MessageCursor> loadSeenMessages();
    void saveMessage(Message message);
    void saveCursor(MessageCursor cursor);
}
```

**关键约束：**
- `saveMessage()` 必须先于 `saveCursor()` 完成
- `loadSeenMessages()` 返回顺序需稳定
- `MemoryCursorStore` 仅适合 demo 和测试

### ClientListener（事件回调接口）

```java
public interface ClientListener {
    void onLogin(LoginInfo info);
    void onMessage(Message message);
    void onPacket(Packet packet);
    void onError(Throwable error);
    void onDisconnect(Throwable error);
}
```

**回调顺序保证：** `onMessage()` 只在 SDK 完成 `saveMessage -> saveCursor` 并尝试 ack 后触发。

### 错误层次

| 异常类 | 继承自 | 说明 |
|--------|--------|------|
| `TurntfException` | `RuntimeException` | 所有 turntf 异常基类 |
| `ConnectionError` | `TurntfException` | 网络/传输错误 |
| `ProtocolError` | `TurntfException` | 协议不匹配 |
| `ServerError` | `TurntfException` | 服务端业务错误 |

---

## Maven 发布说明

### 发布到本地 Maven 仓库

```bash
gradle publishToMavenLocal
```

发布后的坐标：

```kotlin
implementation("io.github.tursom:turntf-java:0.1.0")
```

### 发布到远程仓库

当前构建脚本已启用 `maven-publish` 插件但未预配置远端发布仓库。如需发布到 Maven Central 或私有仓库，需在 `build.gradle.kts` 中添加 publishing 配置：

```kotlin
publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            // 配置 POM 信息
        }
    }
    repositories {
        maven {
            url = uri("https://your-repo.example.com/releases")
            credentials {
                username = project.findProperty("repo.user") as String?
                password = project.findProperty("repo.password") as String?
            }
        }
    }
}
```

### 发布产物包含

当前构建脚本通过 `java.withSourcesJar()` 和 `java.withJavadocJar()` 配置了源码包和 Javadoc 包的生成。

---

## 编码规范

### 通用规则

- 所有源文件使用 UTF-8 编码
- 遵循 Java 标准命名约定（camelCase 方法名、PascalCase 类名、ALL_CAPS 常量）
- 使用 `record` 作为不可变数据载体
- 公开 API 包路径为 `io.github.tursom.turntf.java`
- 内部实现放在 `internal/` 子包中

### 注释要求

文档注释（Javadoc）和行内注释的重点：

1. **协议映射**：Java 模型与 protobuf wire schema 之间的对应关系
2. **连接状态机**：WebSocket 生命周期各阶段的转换条件和行为
3. **自动重连**：指数退避策略、unauthorized 终止条件、seen_messages 快照时机
4. **pending RPC**：断线时未来立即失败的语义，以及为什么不能跨会话复用
5. **消息持久化顺序**：saveMessage 先于 saveCursor 的原因和崩溃恢复影响
6. **ack 时机**：AckMessage 的语义边界（连接内去重，非数据库级确认）
7. **线程语义**：哪些方法在什么线程上执行，回调的线程归属和阻塞注意事项
8. **错误处理边界**：同步参数校验 vs 异步异常完成 vs 回调异常的路由

**不要添加**解释显而易见赋值或简单 getter/setter 的低价值注释。

### 线程安全

- `TurntfClient` 内部使用 `volatile` 变量保护热路径（`sendEnvelope`）的快速检查
- 生命周期转换（socket/auth/loginInfo 的联合更新）通过 `stateLock` 保护
- `pending` RPC 映射表使用 `ConcurrentHashMap`
- `ClientListener` 回调由 SDK 内部线程直接触发，不应在回调内执行阻塞 I/O
- 调度线程池 `ScheduledExecutorService` 为单线程

### 异常设计

- 参数校验错误：`IllegalArgumentException`，同步抛出
- 连接/协议/服务端错误：继承 `TurntfException` 的特定异常类
- `CompletableFuture` 异常完成：`CompletionException` 包装（`join()`）或 `ExecutionException` 包装（`get()`）
- 连接关闭或未认证时：`IllegalStateException`

### 测试要求

- 使用 JUnit 5 + MockWebServer
- HTTP 客户端测试重点：JSON 形状、base64 编解码、Bearer token 注入
- 实时客户端测试重点：登录帧形状、自动 ack、消息持久化顺序、ping/pong 联动
- 新增协议语义时应同步补充测试

---

## 提交规范

### Git 提交作者

所有 git 提交的作者必须是 `tursom <tursom@foxmail.com>`，不允许使用非本人身份作为提交者。

### 提交信息格式

遵循根仓库的约定，使用中文或英文描述，建议简洁的一到两句话说明变更目的。

### 跨模块变更注意事项

- 修改涉及协议映射、消息投递/ack 语义、自动重连、pending RPC、session_ref、游标持久化顺序等共享约束时，检查服务端文档和其他 SDK 是否需要同步更新
- 修改 `turntf-java/proto/client.proto` 时，同步检查 Kotlin SDK 的对应 proto 是否需要更新
- 涉及公共行为时，优先以 `turntf/`（服务端与协议主参考实现）的文档与实现为准

### 提交流程

1. 先改 `proto/client.proto` 或确认服务端协议已变更
2. 执行 `gradle generateProto`
3. 更新 `TurntfClient` / `TurntfHttpClient` / `ProtoAdapters`
4. 更新或新增测试
5. 执行 `gradle test` 确保所有测试通过
6. 更新 README 与专题文档
7. 如涉及共享协议，同步检查 Kotlin SDK
8. 提交使用 `tursom` 身份

---

## 文档维护

- 所有文档使用中文编写
- AGENTS.md 面向 AI 开发助手，涵盖构建、架构和开发流程
- docs/ 目录下存放专题技术文档，按主题拆分
- 每次协议或 API 变更时，需同步更新相关文档
