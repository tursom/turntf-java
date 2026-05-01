# SDK 使用指南

本文档是 turntf-java SDK 的综合使用指南，帮助开发者理解 SDK 的整体架构，在两个客户端之间做出选择，并掌握常见使用模式。

---

## 客户端选择：TurntfHttpClient vs TurntfClient

### 何时使用 TurntfHttpClient

`TurntfHttpClient` 是阻塞式 HTTP 客户端，适合以下场景：

- **管理面脚本**：创建用户、查询消息、管理附件和黑名单
- **后台批处理任务**：批量查询、数据导出
- **一次性管理操作**：修改用户角色、删除资源
- **对延迟不敏感的管理查询**：列举集群节点、查看在线用户

优势：API 简单、调用直观、无需管理 WebSocket 生命周期。

限制：
- 不支持 `resolveUserSessions()`、`operationsStatus()`、`metrics()`、`listEvents()` 等 RPC
- `postPacket()` 不支持 `target_session` 定向
- 没有自动重连能力
- 每次调用独立 HTTP 请求，没有连接复用

### 何时使用 TurntfClient

`TurntfClient` 是 WebSocket 实时客户端，适合以下场景：

- **需要实时推送**：接收消息推送和 packet 推送
- **需要自动重连**：长期运行的守护进程
- **需要消息去重恢复**：断线后通过 `seen_messages` 恢复
- **需要会话定向**：使用 `session_ref` 做定向 packet
- **需要复用连接执行多类 RPC**：同一条 WebSocket 连接上执行各类操作方法

优势：完整的 RPC 覆盖、自动重连、消息可靠性保障、回调通知。

限制：
- 需要管理连接生命周期
- 所有异步返回值用 `CompletableFuture` 封装
- 需要正确实现 `CursorStore` 以获得消息可靠性保障

### 双客户端协作

两个客户端可以通过 `TurntfClient.http()` 互操作。你可以使用实时客户端管理 WebSocket 连接，同时通过关联的 HTTP 客户端做一些简单的管理操作：

```java
TurntfClient client = new TurntfClient(config);
client.connect().join();

// 使用实时客户端做消息监听
// 同时使用 HTTP 客户端做管理操作
String token = client.http().login(4096, 1, "root");
var users = client.http().listNodeLoggedInUsers(token, 4096);
```

---

## 实时客户端连接生命周期

### 连接流程

```
connect() 调用
    │
    ├─ 启动 manager 线程
    ├─ 快照 CursorStore.loadSeenMessages()
    ├─ URL 映射（http→ws / https→wss）
    ├─ OkHttp WebSocket 拨号
    ├─ 发送登录帧（credentials + seen_messages + transient_only）
    │
    ├─ 成功 → 完成 connect() future
    │        ├─ 触发 onLogin()
    │        ├─ 启动定时 ping
    │        └─ 开始处理推送消息、RPC 响应
    │
    └─ 失败 → connect() future 异常完成
             └─ 根据配置决定是否重连
```

### 断线重连流程

```
连接断开
    │
    ├─ 取消 ping 任务
    ├─ 清空认证态（authenticated=false, loginInfo=null, webSocket=null）
    ├─ 失败所有 pending RPC
    ├─ 触发 onDisconnect()
    │
    ├─ 如果允许重连（reconnect=true 且非 unauthorized）
    │      └─ 指数退避等待后重拨
    │
    └─ 如果不允许重连
           └─ 停止
```

### 关键时序点

1. `connect()` 只等待第一次成功认证的会话。后续重连由内部 manager 线程自动处理，不需要再次调用 `connect()`
2. 每次重连前都会重新快照 `loadSeenMessages()`，因此只要本地游标持久化，就能让服务端跳过已消费消息
3. 一旦断线，该连接上的所有 pending RPC 立即失败（被 `CompletionException` 包装）

### 哪些情况不会重连

- 显式调用 `close()`
- `Config.reconnect = false`
- 登录失败且错误码是 `unauthorized`（视为凭证终态错误）

---

## 错误处理模式

### 同步错误（立即抛出）

```java
try {
    TurntfClient client = new TurntfClient(null);
} catch (IllegalArgumentException e) {
    // config 为 null
}
```

### 异步错误（CompletableFuture 异常完成）

```java
client.ping()
    .thenRun(() -> System.out.println("pong received"))
    .exceptionally(err -> {
        Throwable cause = err.getCause() != null ? err.getCause() : err;
        if (cause instanceof TimeoutException) {
            System.out.println("ping timed out");
        } else if (cause instanceof ConnectionError) {
            System.out.println("connection lost: " + cause.getMessage());
        } else if (cause instanceof ServerError se) {
            System.out.println("server error: " + se.code() + ": " + se.getMessage());
        } else {
            System.out.println("unexpected error: " + cause);
        }
        return null;
    });
```

### join() 异常展开

`CompletableFuture.join()` 会把所有异常包装为 `CompletionException`。排障时通常需要查看根因：

```java
try {
    client.sendMessage(input).join();
} catch (RuntimeException e) {
    Throwable cause = e.getCause() == null ? e : e.getCause();
    cause.printStackTrace();
}
```

### 全局错误监听

通过 `ClientListener.onError()` 可以捕获协议异常、非 request-scoped 的服务端错误、ping 异常等：

```java
ClientListener listener = new NopClientListener() {
    @Override
    public void onError(Throwable error) {
        logger.warn("client error", error);
    }
};
```

---

## 常见使用模式

### 模式一：消息接收与持久化

```java
CursorStore store = new MyDatabaseCursorStore();
ClientListener listener = new NopClientListener() {
    @Override
    public void onMessage(Message message) {
        // 此时消息已经过 SDK 持久化（saveMessage + saveCursor）
        // 消息可在业务层安全处理
        processMessage(message);
    }
};

TurntfClient client = new TurntfClient(new Config(
    "http://127.0.0.1:8080",
    new Credentials(4096, 1025, PasswordInput.plain("password")),
    store,
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

// 保持主线程存活
Thread.currentThread().join();
```

### 模式二：批量管理操作（HTTP 客户端）

```java
TurntfHttpClient http = new TurntfHttpClient("http://127.0.0.1:8080");
String adminToken = http.login(4096, 1, "root");

// 批量创建用户
for (int i = 0; i < 10; i++) {
    User user = http.createUser(adminToken,
        new CreateUserRequest("user" + i, PasswordInput.plain("pass"), null, "user"));
    System.out.println("created: " + user.username());
}

// 查询消息
var messages = http.listMessages(adminToken, new UserRef(4096, 1025), 50);
```

### 模式三：会话定向 packet

```java
// 1. 解析目标用户的在线会话
var sessions = client.resolveUserSessions(new UserRef(8192, 1025)).join();

// 2. 选择一个支持 transient 的会话
var session = sessions.sessions().stream()
    .filter(ResolvedUserSessions.ResolvedSession::transientCapable)
    .findFirst()
    .orElseThrow();

// 3. 发送定向 packet
var accepted = client.sendPacketToSession(
    new UserRef(8192, 1025),
    session.session(),
    "ping".getBytes(StandardCharsets.UTF_8),
    DeliveryMode.ROUTE_RETRY
).join();
```

### 模式四：使用简化构造器（demo 用途）

```java
// 简化构造器：自动重连开启、MemoryCursorStore、NopClientListener、ack=true
Config config = new Config(
    "http://127.0.0.1:8080",
    new Credentials(4096, 1025, PasswordInput.plain("password"))
);
TurntfClient client = new TurntfClient(config);
client.connect().join();
```

---

## 生产环境注意事项

### CursorStore 实现

- 不要使用 `MemoryCursorStore`（进程重启后丢失游标）
- 推荐接入数据库、嵌入式 KV（RocksDB、LevelDB）或本地持久文件
- `loadSeenMessages()` 的返回顺序需稳定
- 不要在业务尚未准备好允许消息重放前删除游标
- `saveMessage()` / `saveCursor()` 在接收帧处理路径上同步执行，实现必须线程安全、低延迟

### ClientListener 实现

- 回调由 SDK 内部线程直接触发
- **不要在回调中执行阻塞 I/O 或重 CPU 工作**
- 重活应尽快交给业务线程池处理
- 确保回调实现是线程安全的

### 网络与连接

- 配置合理的 `pingInterval`（默认 30 秒）以检测死连接
- 配置合理的 `requestTimeout`（默认 10 秒）避免 RPC 无限等待
- 生产环境建议使用复用的 `OkHttpClient` 以统一超时、代理和 TLS 策略
- 不要依赖 `close()` 后的客户端实例——创建新实例代替

### Threading 模型

`TurntfClient` 内部使用两个线程：
- **manager 线程**（`turntf-java-client`）：运行主循环，负责 WebSocket 拨号、登录、重连
- **调度线程**（`turntf-java-scheduler`）：`ScheduledExecutorService` 单线程池，负责定时 ping 和 RPC 超时

WebSocket 帧处理发生在 OkHttp 的线程池中。

---

## 排查指南

### 连接问题

| 症状 | 可能原因 | 排查方向 |
|------|----------|----------|
| `connect().join()` 抛出 `ConnectionError` | 服务端不可达 | 检查 baseUrl、网络连通性 |
| `ConnectionError` 带 HTTP 状态码 | WebSocket 升级被拒绝 | 检查服务端配置和日志 |
| 连接后立即断线 | 认证失败 | 检查 `Credentials` 中 nodeId/userId/password |
| 持续重连但总失败 | 网络不稳定或服务端异常 | 查看 `onError()` 回调日志 |

### 消息问题

| 症状 | 可能原因 | 排查方向 |
|------|----------|----------|
| 重连后消息重复 | CursorStore 丢失了游标 | 检查 `loadSeenMessages()` 返回值 |
| 消息丢失 | saveMessage 在 saveCursor 之前失败 | 检查 CursorStore 实现 |
| ack 未发送 | `ackMessages=false` 或连接断开 | 检查 Config 配置 |
| `sendMessage()` 超时 | 网络问题或服务端负载高 | 检查 `requestTimeout` 配置 |

### 异常排查

| 异常类型 | 说明 | 常见原因 |
|----------|------|----------|
| `IllegalArgumentException` | 参数校验失败 | 空密码、非法的 UserRef、非法的 DeliveryMode |
| `IllegalStateException` | 客户端状态不正确 | 客户端已关闭或未连接 |
| `ConnectionError` | 网络/传输错误 | 拨号失败、WebSocket 异常 |
| `ProtocolError` | 协议不匹配 | 收到意外帧、缺少预期字段、protobuf 解码失败 |
| `ServerError` | 服务端业务错误 | 认证失败、授权不足、资源不存在 |
| `TimeoutException` | RPC 超时 | 请求超过 `requestTimeout` |

### 常见错误消息

- `"turntf client is closed"`：客户端已关闭，创建新实例
- `"turntf client is not connected"`：尚未认证或连接已断开
- `"turntf websocket disconnected"`：连接意外断开
- `"turntf server error: unauthorized (...)"`：凭证无效，不会自动重连
