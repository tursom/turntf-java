# 开发环境搭建

本文说明 `turntf-java/` 的开发环境配置、IDE 设置和日常开发流程。

---

## 系统前提

### JDK 21

JDK 21 是编译和运行的最低要求。建议使用以下任一 JDK 发行版：

- **Eclipse Temurin**（推荐）：`https://adoptium.net/`
- **Amazon Corretto**：`https://aws.amazon.com/corretto/`
- **Oracle OpenJDK**

验证安装：

```bash
java -version
# 预期输出类似:
# openjdk version "21" 2023-09-19
# OpenJDK Runtime Environment (build 21+35)
# OpenJDK 64-Bit Server VM (build 21+35, mixed mode, sharing)

javac -version
# 预期输出: javac 21
```

### Gradle

当前项目未附带 Gradle Wrapper，需要系统安装可用的 `gradle`。

```bash
# 验证安装
gradle --version
# 预期输出: Gradle 8.x+
```

推荐安装方式：

- **SDKMAN**（推荐）：`sdk install gradle`
- **Homebrew**（macOS）：`brew install gradle`
- **包管理器**（Linux）：`apt install gradle` 或 `dnf install gradle`

### Git

```bash
git --version
# 预期输出: git 2.x+
```

---

## 项目结构

```
turntf-java/
├── AGENTS.md                    # AI 开发助手指南
├── README.md                    # 项目总览与快速入门
├── build.gradle.kts             # Gradle 构建脚本
├── settings.gradle.kts          # Gradle 项目设置
├── .gitignore                   # Git 忽略规则
├── proto/
│   └── client.proto             # 本地协议定义
├── docs/
│   ├── build-and-proto.md       # 构建与 Proto 同步说明
│   ├── realtime-client.md       # 实时客户端详解
│   ├── sdk-guide.md             # SDK 使用指南
│   ├── http-client.md           # HTTP 客户端使用指南
│   └── development.md           # 开发环境搭建（本文）
├── src/
│   ├── main/java/io/github/tursom/turntf/java/
│   │   ├── TurntfClient.java        # WebSocket 实时客户端
│   │   ├── TurntfHttpClient.java    # HTTP 阻塞客户端
│   │   ├── Config.java              # 运行时配置
│   │   ├── Credentials.java         # 登录身份
│   │   ├── PasswordInput.java       # 密码封装
│   │   ├── ClientListener.java      # 事件回调接口
│   │   ├── CursorStore.java         # 游标持久化接口
│   │   ├── ...                      # 其他模型类和异常类
│   │   └── internal/
│   │       ├── ProtoAdapters.java   # Protobuf 模型映射
│   │       ├── JsonCodec.java       # HTTP JSON 编解码
│   │       └── Validation.java      # 参数校验
│   └── test/java/io/github/tursom/turntf/java/
│       ├── TurntfClientTest.java    # 实时客户端测试
│       └── TurntfHttpClientTest.java # HTTP 客户端测试
└── build/                           # 构建产物（被 .gitignore 忽略）
    └── generated/sources/proto/...  # Protobuf 生成代码
```

---

## IDE 配置

### IntelliJ IDEA（推荐）

1. 打开项目：`File -> Open... -> 选择 turntf-java/ 目录`
2. 确保 JDK 21 已配置：`File -> Project Structure -> Project -> SDK -> 选择 JDK 21`
3. 确保 Gradle 已配置：`File -> Settings -> Build, Execution, Deployment -> Build Tools -> Gradle`，指定本地 Gradle 路径
4. 启用注释处理（如需要）：`File -> Settings -> Build, Execution, Deployment -> Compiler -> Annotation Processors`

建议安装的插件：
- **Protobuf Support**：支持 .proto 文件语法高亮和导航
- **Gradle**：内置，确保启用

导入后的操作：
- 首次打开时，IDEA 会自动索引 Gradle 依赖
- 可以通过 Gradle 工具栏执行 `generateProto` 任务，让 IDEA 识别生成的 protobuf Java 代码

### VS Code / VSCodium

1. 安装扩展：
   - **Extension Pack for Java**（Microsoft）
   - **Gradle for Java**（vscjava）
   - **Protobuf (proto3) Support**（zxh404）

2. 打开项目：`File -> Open Folder... -> 选择 turntf-java/`

---

## Gradle 配置详解

### 构建脚本关键配置

`build.gradle.kts` 中核心内容：

```kotlin
plugins {
    `java-library`
    `maven-publish`
    id("com.google.protobuf") version "0.9.5"
}

group = "io.github.tursom"
version = "0.1.0"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
    withJavadocJar()
    withSourcesJar()
}
```

### 依赖一览

| 依赖 | 类型 | 用途 |
|------|------|------|
| `okhttp:4.12.0` | API | HTTP 客户端 + WebSocket 实现 |
| `jbcrypt:0.4` | API | bcrypt 密码哈希 |
| `jackson-databind:2.18.2` | Implementation | HTTP JSON 序列化/反序列化 |
| `protobuf-java:4.29.3` | Implementation | Protobuf 编解码 |
| `junit-jupiter` | Test | 单元测试框架 |
| `mockwebserver:4.12.0` | Test | HTTP 模拟服务端 |

### 本地 Gradle 属性

如需自定义构建行为，可以在 `~/.gradle/gradle.properties` 或项目根目录的 `gradle.properties` 中设置：

```properties
# 提高构建性能
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.daemon=true

# JVM 参数
org.gradle.jvmargs=-Xmx2048m -XX:MaxMetaspaceSize=512m
```

---

## 日常开发命令

### 编译

```bash
# 仅编译 Java 源码（不含测试）
gradle compileJava

# 编译所有源码（含测试）
gradle compileTestJava
```

### 测试

```bash
# 执行全部测试
gradle test

# 清理后测试
gradle clean test

# 运行特定测试类
gradle test --tests "*TurntfHttpClientTest*"

# 运行特定测试方法
gradle test --tests "*TurntfHttpClientTest*testLogin*"

# 带详细日志
gradle test --info

# HTML 测试报告位置
# build/reports/tests/test/index.html
```

### 打包

```bash
# JAR 包
gradle jar
# 输出位置: build/libs/turntf-java-0.1.0.jar

# 源码包
gradle sourcesJar
# 输出位置: build/libs/turntf-java-0.1.0-sources.jar

# Javadoc 包
gradle javadocJar
# 输出位置: build/libs/turntf-java-0.1.0-javadoc.jar
```

### Proto 生成

```bash
# 重新生成 protobuf Java 代码
gradle generateProto

# 生成后产物位置
# build/generated/sources/proto/main/java/notifier/client/v1/Client.java
```

### 发布

```bash
# 发布到本地 Maven 仓库
gradle publishToMavenLocal
# 本地仓库位置: ~/.m2/repository/io/github/tursom/turntf-java/0.1.0/
```

### 完整构建

```bash
# 清理 -> 生成 Proto -> 编译 -> 测试 -> 打包
gradle clean generateProto test jar
```

---

## 测试详解

### TurntfHttpClientTest

**文件位置**：`src/test/java/io/github/tursom/turntf/java/TurntfHttpClientTest.java`

**验证内容**：
- HTTP 登录时明文密码到 bcrypt 的编码
- `PasswordInput.plain(...)` 的 HTTP JSON 编码
- `byte[] body` 的 base64 编解码
- Bearer token 注入
- 请求路径拼装

**运行方式**：

```bash
gradle test --tests "*TurntfHttpClientTest*"
```

### TurntfClientTest

**文件位置**：`src/test/java/io/github/tursom/turntf/java/TurntfClientTest.java`

**验证内容**：
- WebSocket 首帧登录
- `PasswordInput.plain(...)` 在登录帧中的 bcrypt 结果
- `LoginResponse` 到 `LoginInfo` 的映射
- 推送消息后的自动 ack
- `sendMessage()` / `send_message_response`
- `ping()` / `pong`
- `CursorStore` 与 `ClientListener` 的基本联动

**运行方式**：

```bash
gradle test --tests "*TurntfClientTest*"
```

### 测试建议

1. **TDD 流程**：对于新功能，建议先编写测试再实现
2. **测试覆盖重点**：
   - 协议帧的 wire format 形状
   - 错误路径（参数校验、网络异常、服务端错误）
   - 边界条件（空 body、零值 ref、超时）
3. **Mock 策略**：使用 MockWebServer 模拟 HTTP 服务端，直接构造 protobuf 字节测试 WebSocket 帧处理

---

## 调试技巧

### 日志

当前 SDK 没有引入 SLF4J 或 Log4j 等日志框架。调试可以通过以下方式：

1. **ClientListener.onError()**：监听全局错误
2. **OkHttp 日志拦截器**：

```java
HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
logging.setLevel(HttpLoggingInterceptor.Level.BODY);
OkHttpClient client = new OkHttpClient.Builder()
    .addInterceptor(logging)
    .build();
```

3. **系统属性启用 OkHttp 日志**：

```bash
gradle test -Dokhttp.slf4j.simpleLogger.defaultLogLevel=debug
```

### 断点建议

关键调试断点位置：

- `TurntfClient.connectAttempt()`：观察 WebSocket 拨号和登录帧
- `TurntfClient.handleAuthedEnvelope()`：观察服务端推送消息的解析
- `TurntfClient.persistMessage()`：观察消息持久化顺序
- `TurntfClient.rpc()`：观察 request/response 匹配
- `TurntfClient.failAllPending()`：观察断线时的 pending RPC 清理

### IDE 调试

**IntelliJ IDEA**：
1. 在测试类或方法左侧点击行号，选择 Debug
2. 设置断点后以 Debug 模式运行测试

**远程调试**（需要连接到服务端时）：

```bash
gradle test -Dorg.gradle.jvmargs="-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005"
```

---

## 常见问题

### Protobuf 类找不到

**问题**：编译时提示 `package notifier.client.v1 does not exist`

**原因**：protobuf Java 代码未生成

**解决**：

```bash
gradle generateProto
```

然后在 IDE 中刷新 Gradle 项目。

### MockWebServer 端口冲突

**问题**：测试时出现 `java.net.BindException: Address already in use`

**原因**：前一次测试的 MockWebServer 未正确关闭

**解决**：
- 确保测试的 `@AfterEach` 或 `@AfterAll` 中调用了 `mockWebServer.shutdown()`
- 增加测试间隔

### JDK 版本不匹配

**问题**：Gradle 提示 JDK 版本要求 21

**原因**：系统默认 JDK 版本低于 21

**解决**：
- 安装 JDK 21
- 设置 `JAVA_HOME` 环境变量指向 JDK 21
- 或通过 Gradle Toolchain 自动检测（需要 JDK 21 在本地可用）

### Gradle 缓存问题

**问题**：修改后的代码未生效

**解决**：

```bash
gradle clean build
```

---

## Proto 修改流程

当需要修改 `proto/client.proto` 时，推荐按以下步骤操作：

1. **修改 proto 文件**：在 `proto/client.proto` 中添加或修改消息定义
2. **重新生成 Java 代码**：
   ```bash
   gradle generateProto
   ```
3. **检查模型映射**：确认 `ProtoAdapters.java` 中的转换方法匹配新的 proto 定义
4. **更新客户端实现**：根据需要更新 `TurntfClient.java` 或 `TurntfHttpClient.java`
5. **更新测试**：扩展或修改测试以覆盖新协议行为
6. **运行测试**：
   ```bash
   gradle test
   ```
7. **更新文档**：同步更新 README 和 docs/ 中的相关说明
8. **检查 Kotlin SDK**：如果涉及共享协议变更，同步检查 `turntf-kt/proto/client.proto`

---

## 开发流程建议

### 功能开发

1. 从 `main` 分支创建功能分支
2. 实现功能（可先编写测试）
3. 运行测试确保通过
4. 使用 `tursom` 身份提交
5. 提交信息简洁说明变更目的

### Bug 修复

1. 先编写复现 bug 的测试
2. 确认测试失败
3. 修复 bug
4. 确认测试通过
5. 提交修复

### 代码审查要点

- 协议映射是否正确（ProtoAdapters）
- 连接状态机转换是否正确
- 消息持久化顺序是否保证
- 线程安全是否有问题
- 错误处理是否完整
- 测试覆盖率是否充分
- 文档是否同步更新
