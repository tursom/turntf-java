# HTTP 客户端使用指南

本文聚焦 `TurntfHttpClient` 的使用方式、API 端点映射、数据编码规则和常见注意事项。

---

## 概述

`TurntfHttpClient` 是 turntf-java SDK 提供的阻塞式 HTTP JSON 客户端。它封装了服务端 REST 管理接口，适合脚本、后台任务和管理面操作。

```java
TurntfHttpClient client = new TurntfHttpClient("http://127.0.0.1:8080");
```

### 构造方式

```java
// 使用默认 OkHttpClient
TurntfHttpClient http = new TurntfHttpClient("http://127.0.0.1:8080");

// 复用外部 OkHttpClient（统一超时、代理、TLS 配置）
OkHttpClient ok = new OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .build();
TurntfHttpClient http = new TurntfHttpClient("https://turntf.example.com", ok);
```

### 线程安全

`TurntfHttpClient` 内部通过 OkHttp 的同步调用实现，本身是无状态的。在多数场景下可以安全地在多线程间共享同一个实例。

---

## 认证与会话

### 登录

`login()` 方法会：
1. 在客户端用 bcrypt 哈希明文密码
2. 发送 POST 请求到 `/auth/login`
3. 返回 Bearer token

```java
// 方式一：明文密码（自动 bcrypt）
String token = http.login(4096, 1, "root");

// 方式二：已有 bcrypt 字符串
String token = http.loginWithPassword(4096, 1, PasswordInput.hashed("$2a$10$..."));
```

### Token 使用

所有需要认证的方法都接受 `String token` 作为第一个参数，SDK 自动注入 `Authorization: Bearer <token>` 头。

### 登录注意事项

- `login()` 会在客户端进行 bcrypt 哈希，如果上层系统已经完成了 bcrypt，使用 `PasswordInput.hashed(...)` 避免重复哈希
- 登录 token 的有效期由服务端控制，token 过期后需要重新登录
- 每次 `login()` 调用都会产生一个新的 token

---

## API 端点映射

### 认证

| Java 方法 | HTTP 端点 | 说明 |
|-----------|-----------|------|
| `login()` / `loginWithPassword()` | `POST /auth/login` | 获取认证 token |

### 用户管理

| Java 方法 | HTTP 端点 | 说明 |
|-----------|-----------|------|
| `createUser()` | `POST /users` | 创建用户 |
| `createChannel()` | `POST /users`（role=channel） | 创建频道 |

### 消息

| Java 方法 | HTTP 端点 | 说明 |
|-----------|-----------|------|
| `listMessages(token, target, limit)` | `GET /nodes/{nodeId}/users/{userId}/messages` | 列举消息 |
| `postMessage(token, target, body)` | `POST /nodes/{nodeId}/users/{userId}/messages` | 发送持久消息 |
| `postPacket(token, targetNodeId, relayTarget, body, mode)` | `POST /nodes/{nodeId}/users/{userId}/messages` | 发送 transient packet |

### 附件与关系

| Java 方法 | HTTP 端点 | 说明 |
|-----------|-----------|------|
| `upsertAttachment(token, owner, subject, type, configJson)` | `PUT /nodes/{nodeId}/users/{userId}/attachments/{type}/{subjNodeId}/{subjUserId}` | 创建/更新附件 |
| `deleteAttachment(token, owner, subject, type)` | `DELETE .../attachments/{type}/{subjNodeId}/{subjUserId}` | 删除附件 |
| `listAttachments(token, owner, attachmentType)` | `GET /nodes/{nodeId}/users/{userId}/attachments` | 列举附件 |

### 黑名单

| Java 方法 | HTTP 端点 | 说明 |
|-----------|-----------|------|
| `blockUser()` | `PUT .../attachments/user_blacklist/...` | 拉黑用户 |
| `unblockUser()` | `DELETE .../attachments/user_blacklist/...` | 取消拉黑 |
| `listBlockedUsers()` | `GET .../attachments?attachment_type=user_blacklist` | 列举黑名单 |

### 用户元数据

| Java 方法 | HTTP 端点 | 说明 |
|-----------|-----------|------|
| `getUserMetadata(token, owner, key)` | `GET /nodes/{nid}/users/{uid}/metadata/{key}` | 读取元数据 |
| `upsertUserMetadata(token, owner, key, value, expiresAt)` | `PUT /nodes/{nid}/users/{uid}/metadata/{key}` | 创建/更新元数据 |
| `deleteUserMetadata(token, owner, key)` | `DELETE /nodes/{nid}/users/{uid}/metadata/{key}` | 删除元数据 |
| `scanUserMetadata(token, owner, prefix, after, limit)` | `GET /nodes/{nid}/users/{uid}/metadata` | 扫描元数据 |

### 集群查询

| Java 方法 | HTTP 端点 | 说明 |
|-----------|-----------|------|
| `listClusterNodes(token)` | `GET /cluster/nodes` | 列举集群节点 |
| `listNodeLoggedInUsers(token, nodeId)` | `GET /cluster/nodes/{nodeId}/logged-in-users` | 列举节点在线用户 |

---

## 数据编码规则

### byte[] 与 base64

HTTP JSON API 中使用 base64 编码传输二进制数据。SDK 在以下场景自动处理编解码：

- `postMessage()`：`byte[] body` 自动编码为 JSON base64 字段
- `listMessages()`：响应中的 base64 body 自动解码为 `byte[]`
- `upsertAttachment()`：`byte[] configJson` 作为嵌入式 JSON（非 base64）写入 HTTP 请求

```java
// byte[] body 自动编码为 base64
Message msg = http.postMessage(token, target, "hello".getBytes(StandardCharsets.UTF_8));

// 响应中的 base64 自动解码为 byte[]
List<Message> messages = http.listMessages(token, target, 10);
byte[] body = messages.get(0).body();
```

### config_json 的特殊处理

附件操作中的 `config_json` 字段在 HTTP API 中作为嵌入式 JSON（非 base64）传输。SDK 会：

1. 解析 `byte[] configJson` 参数为 JSON 节点
2. 将其嵌入外层请求体中的 `config_json` 字段

```java
byte[] config = "{\"tier\":\"gold\"}".getBytes(StandardCharsets.UTF_8);
Attachment attachment = http.upsertAttachment(token, owner, subject, 
    AttachmentType.CHANNEL_SUBSCRIPTION, config);
```

### PasswordInput 的编码

- `PasswordInput.plain("...")`：创建用户时，SDK 将 bcrypt 哈希结果填入 HTTP 请求的 `password` 字段
- `PasswordInput.hashed("$2a$...")`：直接使用已有 bcrypt 字符串

---

## 常见使用模式

### 用户创建与消息发送

```java
TurntfHttpClient http = new TurntfHttpClient("http://127.0.0.1:8080");
String token = http.login(4096, 1, "root");

// 创建用户
User user = http.createUser(token, new CreateUserRequest(
    "alice",
    PasswordInput.plain("alice-password"),
    "{\"tier\":\"gold\"}".getBytes(StandardCharsets.UTF_8),
    "user"
));

// 发送消息
UserRef recipient = new UserRef(user.nodeId(), user.userId());
Message sent = http.postMessage(token, recipient, "Hello!".getBytes(StandardCharsets.UTF_8));

// 查询消息
List<Message> inbox = http.listMessages(token, recipient, 20);
```

### 附件管理

```java
// 创建附件
byte[] config = "{\"notify\":true}".getBytes(StandardCharsets.UTF_8);
Attachment attachment = http.upsertAttachment(token, owner, subject,
    AttachmentType.CHANNEL_MANAGER, config);

// 列举附件
List<Attachment> attachments = http.listAttachments(token, owner, AttachmentType.CHANNEL_MANAGER);

// 删除附件
Attachment deleted = http.deleteAttachment(token, owner, subject, AttachmentType.CHANNEL_MANAGER);
```

### 黑名单管理

```java
// 拉黑用户
BlacklistEntry entry = http.blockUser(token, owner, blocked);

// 列举黑名单
List<BlacklistEntry> blockedUsers = http.listBlockedUsers(token, owner);

// 解除拉黑
http.unblockUser(token, owner, blocked);
```

### 元数据管理

```java
// 写入元数据
UserMetadata meta = http.upsertUserMetadata(token, owner, "preferences", 
    "{\"theme\":\"dark\"}".getBytes(), null);

// 读取元数据
UserMetadata read = http.getUserMetadata(token, owner, "preferences");

// 扫描元数据（按前缀）
UserMetadataScanResult result = http.scanUserMetadata(token, owner, "pref", null, 100);

// 删除元数据
UserMetadata deleted = http.deleteUserMetadata(token, owner, "preferences");
```

### Transient Packet

```java
// 发送 transient packet（HTTP 端不支持 target_session）
http.postPacket(token, 4096, new UserRef(4096, 1025),
    "ping".getBytes(StandardCharsets.UTF_8), DeliveryMode.BEST_EFFORT);
```

---

## 错误处理

### HTTP 状态码检查

`doJson()` 方法会验证响应状态码是否在 `wantStatuses` 列表中。如果状态码不匹配，抛出 `ProtocolError`：

```java
try {
    http.listMessages(token, new UserRef(0, 0), 10);
} catch (ProtocolError e) {
    // 输出类似: "unexpected HTTP status 500: Internal server error"
    System.err.println(e.getMessage());
}
```

### 常见 HTTP 状态码

| 状态码 | 含义 | 常见原因 |
|--------|------|----------|
| 200 | 成功 | 正常响应 |
| 201 | 创建成功 | POST / PUT 资源创建 |
| 202 | 已接受 | packet 路由请求 |
| 400 | 请求错误 | 参数格式不正确 |
| 401 | 未认证 | token 缺失或无效 |
| 403 | 无权限 | 当前用户无权执行操作 |
| 404 | 资源不存在 | 用户、消息等未找到 |
| 500 | 服务端错误 | 服务端内部异常 |

### 异常类型

- `IllegalArgumentException`：参数校验失败（空密码、非法 UserRef、非法的 DeliveryMode）
- `ProtocolError`：HTTP 状态码不符合预期，或响应体格式异常
- `ConnectionError`：网络异常（连接超时、DNS 解析失败等）

---

## 限制说明

### TurntfHttpClient 不支持的 RPC

以下能力仅在 WebSocket 客户端 `TurntfClient` 中可用：

- `resolveUserSessions()`：解析用户在线会话
- `operationsStatus()`：查询运维状态
- `metrics()`：获取 metrics 文本
- `listEvents()`：列举事件
- `sendPacketToSession()`：会话定向 packet（HTTP 端 `postPacket()` 不支持 `target_session`）

### HTTP 与 WebSocket 的行为差异

- HTTP `postMessage()` 每次调用创建一个独立的 HTTP 请求
- HTTP `postPacket()` 的 `DeliveryMode` 需要显式编码在 JSON 中（WebSocket 端通过 protobuf 枚举传递）
- HTTP 端的附件 `config_json` 作为嵌入式 JSON（WebSocket 端作为 protobuf `bytes` 字段）
- HTTP 登录返回 token（WebSocket 登录返回 `LoginInfo`，包含 `session_ref`）

### 其他注意事项

- `postPacket()` 只能发一般的 transient packet，不支持 `target_session`
- `createUser()` 和 `postMessage()` 接受 200 和 201 作为成功状态码
- `createSubscription()` 内部转发到 `upsertAttachment()`
- 出现非预期 HTTP 状态码时，SDK 会抛出 `ProtocolError` 并尽量带上服务端响应体
