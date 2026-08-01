# Java 实时客户端详解

本文聚焦 `TurntfClient` 这条实时接入路径，解释它和共享协议之间的对应关系，重点覆盖：

- `CompletableFuture` 调用模型
- `Config` / `Credentials` / `CursorStore` / `ClientListener`
- 自动重连与重登录
- `session_ref`
- 消息可靠性与 `saveMessage -> saveCursor -> ack`
- 会话定向 transient packet
- 错误模型

如果你只需要一次性管理请求，可以先看上层总览中的 HTTP 客户端部分：[`README.md`](../README.md)。

## 连接与登录生命周期

`TurntfClient` 的内部流程可以概括成下面几步：

1. `connect()` 被调用后，SDK 启动内部 manager 线程
2. 在真正拨号前，先从 `CursorStore.loadSeenMessages()` 读取本地已持久化游标快照
3. 把 `baseUrl` 映射成 WebSocket 地址：
   - 默认 `.../ws/client`
   - 若 `Config.realtimeStream = true`，则为 `.../ws/realtime`
4. WebSocket 升级成功后，第一帧必须发送 `ClientEnvelope.login`
5. 登录帧携带：
   - `Credentials` 中二选一的登录选择器：`(node_id, user_id)` 或 `login_name`
   - `Credentials` 中的 `password`
   - SDK 内部固定的 `protocol_version = "client-v1alpha5"`，不提供配置项
   - 本地游标快照 `seen_messages`
   - `Config.transientOnly`
6. SDK 等待首个服务端帧：
   - 若是 `login_response`，先校验服务端版本为 `client-v1alpha5`，再发布 `LoginInfo`、完成 `connect()` 返回的 future、触发 `onLogin()`
   - 若是 `error`，把它转换成 `ServerError`
   - 若既不是登录成功也不是错误，则视为协议异常 `ProtocolError`
7. 登录成功后，SDK 开始处理：
   - `MessagePushed`
   - `PacketPushed`
   - 各类 RPC 响应
   - `error`
   - `pong`
8. 同时启动定时 `ping()`
9. 一旦连接断开，SDK 清空认证态，失败当前连接上的所有 pending RPC，并根据配置决定是否重连

两个很重要的时序点：

- `connect()` 只等待“第一个可用会话”，后续重连不需要再次调用 `connect()`
- 每次重连前重新快照 `loadSeenMessages()`，因此只要本地游标是持久化的，就能让服务端在新连接上跳过已消费消息

## `Config`

`Config` 决定了实时客户端的所有关键行为：

```java
public record Config(
    String baseUrl,
    Credentials credentials,
    CursorStore cursorStore,
    ClientListener listener,
    OkHttpClient httpClient,
    boolean reconnect,
    Duration initialReconnectDelay,
    Duration maxReconnectDelay,
    Duration pingInterval,
    Duration requestTimeout,
    boolean ackMessages,
    boolean transientOnly,
    boolean realtimeStream
) { ... }
```

建议这样理解这些字段：

- `baseUrl`
  HTTP/WS 的公共基地址。SDK 会自动完成 `http -> ws` / `https -> wss` 映射，不需要手动拼 `/ws/client`
- `credentials`
  WebSocket 首帧登录身份。它和 `TurntfHttpClient.login()` 的身份字段一致
- `cursorStore`
  可靠重连的根基。为空时 SDK 会退回 `MemoryCursorStore`
- `listener`
  业务回调实现。为空时 SDK 会使用 `NopClientListener`
- `httpClient`
  复用的 OkHttp 客户端，可统一超时、代理、TLS 或连接池策略
- `reconnect`
  是否在断线后自动重试
- `initialReconnectDelay` / `maxReconnectDelay`
  失败重试的指数退避区间。成功登录一次后，退避会重置回初始值
- `pingInterval`
  应用层 ping 间隔。SDK 会定时发送 `Ping`，并用普通 RPC 超时语义等待 `Pong`
- `requestTimeout`
  所有 RPC 的统一超时，包括 `ping()`、`sendMessage()`、`createUser()` 等
- `ackMessages`
  是否在消息本地持久化完成后自动发送 `AckMessage`
- `transientOnly`
  登录时写入 `LoginRequest.transient_only`；通常用于只接收在线瞬时流的会话策略
- `realtimeStream`
  切换到 `/ws/realtime` 路径

如果你用的是 `new Config(baseUrl, credentials)` 这个简化构造器，默认行为是：

- 自动重连开启
- `MemoryCursorStore`
- `NopClientListener`
- `ackMessages = true`
- `transientOnly = false`
- `realtimeStream = false`

这适合 demo，不适合生产持久化。

## `Credentials` 与 `PasswordInput`

`Credentials` 保存两部分信息：

- 一种且仅一种登录选择器：`(nodeId, userId)` 或 `loginName`
- `password`

其中 `password` 使用 `PasswordInput` 封装：

```java
new Credentials(4096, 1025, PasswordInput.plain("alice-password"))
new Credentials("alice.login", PasswordInput.plain("alice-password"))
```

`PasswordInput` 有两种来源：

- `PasswordInput.plain("...")`
  立刻在客户端生成 bcrypt 哈希
- `PasswordInput.hashed("$2a$...")`
  直接复用已经存在的 bcrypt 字符串

这有两个意义：

- WebSocket 登录和 HTTP 登录可以共用同一套密码封装
- 登录名模式与旧 ID 模式可以共用同一个 `Credentials` 类型，而不需要切换另一套配置对象
- 如果你的上层系统已经管理了 bcrypt，不需要再次哈希

## `list_users` 与可通讯用户列表

实时客户端新增了 `TurntfClient.listUsers(...)`，对应服务端的 `list_users` protobuf RPC。它和
HTTP `GET /users` 保持同一套业务语义：返回“当前登录用户可通讯的活跃用户集合”，并支持：

- `name`：大小写不敏感子串匹配
- `uid`：`UserRef` 精确过滤

Java SDK 暴露统一的 `ListUsersFilter`：

```java
List<User> contacts = client.listUsers(new ListUsersFilter(
    "carol",
    new UserRef(4096, 1027)
)).join();
```

这里需要特别区分两种 wire 形态：

- HTTP `TurntfHttpClient.listUsers(...)` 会把 `uid` 编码成 `node_id:user_id` 查询字符串
- WebSocket `TurntfClient.listUsers(...)` 会把 `uid` 直接编码到 protobuf `UserRef`

SDK 会在发包前执行同一套校验：

- `uid == null` 或 `uid == new UserRef(0, 0)` 表示“不按 uid 过滤”
- 半空 uid 会抛 `IllegalArgumentException`

返回结果里的 `User.loginName()` 也要按服务端可见性理解：

- 管理员或查看自己时，`loginName` 保持可见
- 普通用户查看其他联系人时，`loginName` 可能为空字符串

## `CursorStore`

`CursorStore` 是 turntf 可靠重连语义在 Java 侧的落点：

```java
public interface CursorStore {
    List<MessageCursor> loadSeenMessages();
    void saveMessage(Message message);
    void saveCursor(MessageCursor cursor);
}
```

### 契约

- `loadSeenMessages()`
  返回“本地已经 durable 的消息游标”。SDK 会在每次登录前快照这个列表，并写入 `LoginRequest.seen_messages`
- `saveMessage()`
  用于先保存完整消息体
- `saveCursor()`
  用于记录游标已见；即使消息体已经归档到别处，登录重放只需要这个游标

### 为什么必须先 `saveMessage()` 再 `saveCursor()`

一旦游标被持久化，下一次登录时服务端就会把它视为“客户端已经拥有这条消息”，并据此跳过重发。如果此时消息体还没落盘，进程崩溃后就会出现：

- 服务端认为消息已经被消费
- 客户端本地却没有消息内容

因此，正确顺序必须是：

1. `saveMessage(message)`
2. `saveCursor(message.cursor())`
3. 之后才允许 ack 或继续向业务层分发

`TurntfClient` 在内部已经严格按这个顺序调用。

### 实现建议

- 真实业务里，把 `CursorStore` 接到数据库、嵌入式 KV 或本地持久文件，而不是只用内存
- `loadSeenMessages()` 的返回顺序要稳定
- 不要在业务尚未准备好允许消息重放前删除游标
- `saveMessage()` / `saveCursor()` 会在接收帧的处理路径上同步执行，因此实现必须线程安全、低延迟，并且明确自己的持久化边界

`MemoryCursorStore` 仅用于：

- 单元测试
- demo
- 进程退出即放弃状态的短任务

## `ClientListener`

`ClientListener` 提供五个回调：

```java
public interface ClientListener {
    void onLogin(LoginInfo info);
    void onMessage(Message message);
    void onPacket(Packet packet);
    void onError(Throwable error);
    void onDisconnect(Throwable error);
}
```

### 回调语义

- `onLogin()`
  当前 socket 已完成认证，可以开始安全发送 RPC
- `onMessage()`
  只会在消息已经完成本地持久化流程之后触发；如果启用了自动 ack，SDK 也已经尝试发送 `AckMessage`
- `onPacket()`
  收到 transient packet 后立刻触发。packet 没有游标，不经过 `CursorStore`
- `onError()`
  用于报告协议异常、非 request-scoped 的服务端错误、定时 ping 的异常、可重试错误等
- `onDisconnect()`
  当前连接真正结束时触发；无论后续是否重连，都会先走这一步

### 线程与阻塞注意事项

回调由 SDK 的内部线程直接触发：

- WebSocket 帧处理线程负责大部分 `onLogin()`、`onMessage()`、`onPacket()`
- 调度线程负责定时 ping 及其错误回调

因此：

- 不要在回调里做长时间阻塞 I/O
- 不要在回调里执行不可控的重 CPU 工作
- 如果业务处理较重，应该尽快把消息移交给自己的线程池或队列

## 自动重连、重登录与 pending RPC

### 重连策略

当当前连接因为网络或服务端关闭而失效时，SDK 会：

1. 取消 ping 任务
2. 清空 `authenticated`、`loginInfo`、`webSocket`
3. 让当前连接上的所有 pending RPC 立即失败
4. 触发 `onDisconnect(error)`
5. 如果允许重连，则按指数退避等待后重拨

### 哪些情况不会重连

- 显式调用了 `close()`
- `Config.reconnect = false`
- 登录失败且错误码是 `unauthorized`
- 登录失败且错误码是 `unsupported_protocol_version`
- 成功响应的 `protocol_version` 为空或不是 `client-v1alpha5`

`unauthorized` 是当前凭证的终态错误。版本错误则表示 SDK 与服务端使用不同的 wire epoch；历史 envelope tag 曾复用，不能安全混跑。两类错误都无法通过重拨修复，因此 SDK 会在发布登录状态前失败并停止重连。

### pending RPC 为什么会在断线时立刻失败

所有 request/response RPC 都依附于“发送它的那条 WebSocket 会话”。一旦该 socket 失效：

- 旧请求不可能从新 socket 获得响应
- 等待到超时只会拖慢业务恢复速度

因此 SDK 在断线时会立即让这些 future 失败，业务应自行决定是否重试。

## `session_ref` 与定向 transient packet

### `session_ref` 是什么

登录成功时，服务端会返回：

- `user`
- `protocol_version`
- `session_ref`

其中 `session_ref` 标识“当前这次登录对应的在线 session”。Java SDK 把它暴露在：

- `LoginInfo.sessionRef()`
- `TurntfClient.currentLogin()`

### 它能做什么

`session_ref` 主要用于会话定向的 transient packet：

1. 调用 `resolveUserSessions(user)` 获取目标用户当前在线 session 列表
2. 选择一个 `ResolvedSession.session`
3. 用 `sendPacketToSession(...)` 或 `sendPacket(new SendPacketInput(...))` 指定 `targetSession`

示例：

```java
var target = new UserRef(8192, 1025);
var sessions = client.resolveUserSessions(target).join();
var chosen = sessions.sessions().stream()
    .filter(item -> item.transientCapable())
    .findFirst()
    .orElseThrow();

var accepted = client.sendPacketToSession(
    target,
    chosen.session(),
    "ping".getBytes(),
    DeliveryMode.ROUTE_RETRY
).join();
```

### 零值 `SessionRef`

Java SDK 对“可选的 `target_session` 缺失”不返回 `null`，而是返回零值：

```java
new SessionRef(0, "")
```

这样做是为了让公开 API 尽量保持 null-free。判断方式：

- `sessionRef.isZero()` 表示线缆上没有这个字段
- `sessionRef.valid()` 表示它是一个可用于投递的真实 session

### 约束

- `target_session` 只允许用于 transient packet
- `sendPacket()` 只接受 `DeliveryMode.BEST_EFFORT` 或 `DeliveryMode.ROUTE_RETRY`
- `sendMessage()` 是持久化消息发送，不允许带 `target_session`
- 服务端返回 `RelayAccepted` 只表示 packet 已进入本地路由层，不代表目标端已经收到

## 消息可靠性与自动 ack

### 推送消息

当服务端发送 `MessagePushed` 时，Java SDK 的顺序是固定的：

1. `ProtoAdapters` 把 protobuf `Message` 转成公开模型
2. `persistMessage(message)`：
   - `cursorStore.saveMessage(message)`
   - `cursorStore.saveCursor(message.cursor())`
3. 如果 `ackMessages = true`
   - 发送 `AckMessage`
4. 触发 `listener.onMessage(message)`

### 为什么 `AckMessage` 不是可靠性的核心

共享协议里，`AckMessage` 只是“当前连接内的去重提示”，并不会把 ack 状态写入服务端数据库。真正保证重连后不重复展示的是：

- 客户端本地游标 durable
- 下一次登录重新上报 `seen_messages`

所以：

- 即使禁用自动 ack，只要下次登录能带上完整 `seen_messages`，仍然可以避免重复投递
- 反过来，即使 ack 已经发出，只要本地没有持久化游标，重启后依然无法可靠恢复

### `sendMessage()` 的特殊点

持久化消息发送成功时，服务端返回 `send_message_response.message`。SDK 会把这条“服务端确认后的最终消息”也写入本地 store，然后才完成返回的 future。这样可以保证：

- 主动发送出去的持久化消息也进入同一套游标体系
- 重连时 `seen_messages` 对发送端和接收端的本地视图更一致

### transient packet 不参与这套流程

`PacketPushed` 和 `RelayAccepted` 没有 `(node_id, seq)` 游标，因此：

- 不进入 `CursorStore`
- 不会生成 `AckMessage`
- 不会在重连后补发

## 错误模型

`TurntfClient` 既有同步错误，也有异步错误。

### 同步抛出

下面这些错误通常会在方法调用时直接抛出或通过 failed future 立即可见：

- `IllegalArgumentException`
  - `baseUrl`、`Credentials`、`UserRef`、`SessionRef`、`DeliveryMode` 不合法
  - 空消息体
- `IllegalStateException`
  - 客户端已关闭
  - 当前尚未连接或尚未完成认证

### 异步完成异常

这些错误更多出现在 `CompletableFuture` 的异常完成里：

- `ConnectionError`
  - 拨号失败
  - HTTP 或 WebSocket 网络异常
- `ProtocolError`
  - 登录阶段收到意外帧
  - 服务端响应缺少 SDK 预期字段
  - protobuf 解码失败
- `ServerError`
  - 服务端显式返回 `error`
  - 带 `request_id` 时会只失败对应 RPC
  - 不带 `request_id` 时通常走 `onError()`
- `TimeoutException`
  - 单个 RPC 超过 `requestTimeout`

### `join()` / `get()` 的包装

- `future.join()` 会把底层异常包装为 `CompletionException`
- `future.get()` 会包装为 `ExecutionException`

业务排障时，通常应该检查根因：

```java
try {
    client.ping().join();
} catch (RuntimeException e) {
    Throwable cause = e.getCause() == null ? e : e.getCause();
    cause.printStackTrace();
}
```

## 实战建议

- 生产环境不要直接依赖 `MemoryCursorStore`
- 把 `CursorStore` 的持久化边界定义清楚，尤其是崩溃恢复场景
- 把 `ClientListener` 当作轻量回调层，重活尽快转交业务线程池
- 对所有 `CompletableFuture` 都处理异常完成，不要只写 happy path
- 如果业务依赖会话定向 packet，把 `session_ref` 和 `resolveUserSessions()` 一起纳入自己的在线路由逻辑
- 协议层改动时，同时检查：
  - `proto/client.proto`
  - `TurntfClient`
  - `internal/ProtoAdapters`
  - 测试
  - 文档
