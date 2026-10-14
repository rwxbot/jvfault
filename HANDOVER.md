# HANDOVER — jvfault Java 框架会话交接

**会话时间**: 2026-09-22
**最终状态**: 12 标签 roadmap 全部实现、CI/测试套件/真实传输/Maven 发布链路已落地，git 历史按版本纪元重建并强推至远程
**接手给**: 下一位 Agent（建议：同会话串接继续，或以本工作区为起点独立推进）

---

## 1. 一句话现状

`jvfault` 是一个注解驱动的模块化纯 Java 框架，**完整实现且全部测试通过**：
- 39 个框架模块 + 12 个版本示例（v0.1.0 - v1.0.0 每版本一个）
- 13 个 git commit（每个版本一个）+ 12 个 tag（v0.1.0 ~ v1.0.0 全含）
- **274 测试 0 失败**（含 7 个 Jetty+JWT 端到端 + 真实集成：3 redis + 3 kafka + 3 rabbitmq + 3 nats + 3 mqtt + 3 grpc；skip 数随本地 broker 浮动）
- 40 个构件已实测发布到本地 m2（`./gradlew publishToMavenLocal`）
- 远程仓库：`git@github.com:rwxbot/jvfault.git`（仅 main 分支，身份 johnnynode <johnnynode@gmail.com>）

### 1.1 第二轮（2026-09-22 晚）修复与补全

| 项 | 问题 | 处理 |
|----|------|------|
| 示例闭环 | `settings.gradle.kts` 只注册 10 个示例，**缺 v0.9.0（plugin/apt/aot）与 v1.0.0（security/compliance/migration/ops）** | 新建两个可运行示例并注册，现 12/12 |
| apt 真失效 | 缺 `META-INF/services/javax.annotation.processing.Processor`，消费者挂 `annotationProcessor` 时处理器**从不运行** | 补注册 + 加真实 javac 编译测试，示例输出已非空 |
| CI 空跑 | CI 无 broker service，传输集成测试静默跳过 | 加 redis/kafka service + `JVFAULT_*_BOOTSTRAP` 环境变量契约 |
| kafka 真实集成 | 此前只有单元测试，未连过真 broker | 新增 `KafkaIntegrationTest`（3 例，真实容器/CI service 双通道） |
| Kafka bug A | `close()` 从调用线程关 consumer，与 poll 线程冲突 → `ConcurrentModificationException` | 改由轮询线程关闭 consumer，`close()` 只 wakeup + 等待 |
| Kafka bug B | 无订阅时 `bind()` 直接 `poll()` → `IllegalStateException` | 仅在有 handler 订阅时触发首次 poll |

### 1.3 第三轮（2026-09-22 晚）传输层真实集成补全

| 项 | 问题 | 处理 |
|----|------|------|
| rmq/nats/mqtt/grpc 占位 | 4 个传输仅「配置校验 + 惰性生命周期 + 客户端库适配骨架」，从未连过真服务 | 全部升级为**真实实现**并端到端验证 |
| CI 覆盖面 | CI 只有 redis/kafka service | 加 rabbitmq / nats / mosquitto service 与 `JVFAULT_RMQ/NATS/MQTT_BOOTSTRAP`（grpc 走本机回环，无需 service） |
| 版本同步 | 新增功能与构件版本脱节 | `jvfaultVersion` 1.0.1 → 1.0.2 |

真实集成测试：`RabbitMQIntegrationTest` / `NATSIntegrationTest` / `MQTTIntegrationTest` / `GrpcIntegrationTest`
各 3 例（request-reply、error 头回传、事件语义），本地实测 **12/12 全绿、0 跳过**（rabbitmq:3-management、
nats-server 2.15.0、eclipse-mosquitto:1.6 真实 broker；grpc 走 grpc-netty 本机回环）。测试总数 251 → 263。

## 2. 工作区结构

```
/Users/Wang/Code/webfault-lib/jvfault-all/
├── jvfault/          # ★ 独立 Git 仓库（已强推），框架本体
│   ├── core/ aop/ config/ validation/ exception/ logging/
│   ├── web/ platform-servlet/ platform-reactive/ websocket/ sse/
│   ├── openapi/ graphql/ security/
│   ├── microservices/ transport-{tcp,grpc,kafka,redis,nats,rmq,mqtt}/
│   ├── scheduling/ cache/ tracing/ metrics/
│   ├── plugin/ apt/ aot/ native/ virtualthreads/
│   ├── ai/ rag/ mcp/ compliance/ migration/ ops/
│   ├── test/ tests/ examples/v0.*/
│   ├── .github/workflows/ci.yml
│   ├── build.gradle.kts / settings.gradle.kts / gradlew
│   └── README.md / CHANGELOG.md / docs/architecture-decisions.md
├── .jvfault-memory/   # 本地（不入库）— 参考分析与阶段规划
├── .jvfault-state/    # 本地（不入库）— 当前 phase JSON
├── reference/         # 本地（不入库）— 外部参考资料
└── .gitignore / README.md / AGENT_HANDOFF.md  # 仅这三个文件入库
```

## 3. 关键命令（接手时立刻可跑）

```bash
# 全框架构建 + 245 测试
cd /Users/Wang/Code/webfault-lib/jvfault-all/jvfault
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew build

# 单独模块测试
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :core:test :redis:test :web:test

# 完整发布到本地 m2
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew publishToMavenLocal

# 真实传输集成（需要 Docker 守护进程）
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :transport-redis:test
# 测试通过时实际启动 redis:7.2-alpine 容器跑 psubscribe+replyChannel+error 头全链路

# 强推远程（用户偏好：直接覆盖）
git remote add origin git@github.com:rwxbot/jvfault.git
git push --force -u origin main
git push --force origin --tags
```

## 4. 架构与决策要点（详见 docs/architecture-decisions.md）

| ADR | 决策 |
|-----|------|
| 001 | 纯 Java（无 Kotlin / Shadow / Checkstyle），仅 Gradle 内置插件 |
| 002 | JDK 21 作 Gradle JVM，框架模块 --release 8，AI/2025 模块 --release 17 |
| 003 | 移除 module-info.java（v0.x 阶段无 JPMS 需求） |
| 004 | BeanPostProcessor 是 aop/config/scheduling 接入点 |
| 005 | jvfault/ 独立组合构建，父 monorepo 通过 dependencySubstitution 消费 |
| 006 | 依赖选型：ByteBuddy 1.14.9（aop 类代理 INJECTION 策略）、ClassGraph 4.8.168、caffeine 2.9.3、JUnit 5.10.2 |
| 007 | transport-tcp 用 JDK NIO（不引入 Netty） |
| 008 | 框架拆分为独立仓库，monorepo 退为 Agent 工作区 |
| 009 | distribution BOM 移除（与 java-platform 互斥且冗余，消费者用 Gradle version catalogs 对齐） |

## 5. 用户偏好（严格遵守）

- **内容红线**：仓库任何文件/任何提交**不得出现其他语言框架的名称对照表述**（含 javadoc "对应 XX:" 行；Spring 相关注释可保留）
- **git 偏好**：明确指示强推（`--force`），指定邮箱 `johnnynode@gmail.com`
- **构建 JVM 偏好**：必须用 JDK 21 作 Gradle JVM（`JAVA_HOME=$(/usr/libexec/java_home -v 21)`，系统默认 Java 是 12）
- **架构偏好**：注解驱动、模块化、类型安全；以 Java 标准（JSR-330/250/380、SPI）实现
- **语言偏好**：用户用中文沟通，文档与示例偏向现代框架术语

## 6. 接手时已确认的踩过的坑

1. **`String.trim()` 会吃掉 ≤ U+0020 的所有字符**：aop 之前用 `\u0002` 当跨段占位符，调用 `.trim()` 时被吞掉，**导致跨段通配失效**。修复：用三态匹配（"*" 占位独立判断，不依赖 trim）。
2. **ByteBuddy 包私有类代理**：默认策略抛 `IllegalAccessError`。**必须**用 `ClassLoadingStrategy.Default.INJECTION` 策略（见 aop/ByteBuddyClassProxy）。
3. **redis 客户端订阅连接禁止 PUBLISH**：必须**两条独立连接**——一条订阅，一条发布（详见 transport-redis/RedisTransportServer/Client）。
4. **Testcontainers 拉镜像超时**：本地网络受 Docker Hub 限流。**改用 docker CLI + 固定端口 `--publish=9092:9092`**（见 transport-redis/DockerContainer.java 模板）。
5. **ByteBuddy `*Service` 与 JDK 内部类同名冲突**：用 `@Override`+`new String[]{}` 避免。
6. **java-platform 与 java-library 互斥**：distribution BOM 模块需在 subprojects{} 排除。本项目**已删除 distribution 模块**（消费者用文档/version catalog 对齐版本）。
7. **kafka-clients 字段名以 `_CONFIG` 后缀结尾**：用 `ConsumerConfig.GROUP_ID_CONFIG`（不是 `GROUP_ID`）。
8. **ZonedDateTime 无 `plusMillis`**：用 `.plus(millis, ChronoUnit.MILLIS)`。
9. **jshell 不可用**（用户 macOS 默认 JDK 12）：统一用 `JAVA_HOME=$(/usr/libexec/java_home -v 21)` 切到 JDK 21。
10. **子代理有并发限制（同时只能跑 1 个）**：批量并行派发会报 "user concurrency limit exceeded"。**建议主线程亲自实现，不要批量派发子代理**。

## 7. 下一步建议（按优先级）

### 7.1 【已完成 v1.0.2】CI 真实 broker services
`.github/workflows/ci.yml` 已加 `services:` 段与 `JVFAULT_*_BOOTSTRAP` 契约：
- `redis:7.2-alpine` / `apache/kafka:3.7.0`(KRaft) / `rabbitmq:3-management` / `nats:2.10-alpine` / `eclipse-mosquitto:1.6`
- grpc **无需** service：服务端以 grpc-netty 绑定随机端口本机回环，集成测试直接连
- 现状：7 个传输各 3 例真实集成测试（rabbitmq/nats/mosquitto/grpc 为 v1.0.2 新增），本地已实测全绿

### 7.2 【已完成 v1.0.3】JPMS 多版本 JAR（保留 Java 8 基线）
- **v1.0.2 第一步**：40 个主构件写入 `Automatic-Module-Name: com.jvfault.<name>`（Java 8 侧可用）。
- **决策**：`module-info.java` 只能以 `--release 9+` 编译，与全局 `options.release = 8`（ADR-002）
  冲突，且 ADR-003 曾主动移除 module-info → **选定路径 ①：多版本 JAR（MR-JAR）**，保住 Java 8 基线。
- **机制（v1.0.3）**：根 `build.gradle.kts` 一旦检测到 `src/main/java9/module-info.java`，即
  以 `--release 9` **单独**编译该描述符（`--patch-module` 打补丁 + 依赖以 **JAR** 形态作 module path，
  项目依赖经 `artifactView(LibraryElements.JAR)` 取 jar），产物置于 `META-INF/versions/9/`，
  manifest 置 `Multi-Release: true`。**主代码仍 `--release 8`。**
- **首批 4 个模块**：`com.jvfault.core` / `.exception` / `.logging` / `.metrics`
  （`api` 依赖映射为 `requires transitive`；反射场景由**消费方** `opens ... to com.jvfault.core`）。
- **验证**：主类字节码 major 52（Java 8）、描述符 major 53（Java 9）；
  模块化消费者实机 `javac --module-path` 编译 + `java -m` 运行通过，
  `requires com.jvfault.core` + `com.jvfault.exception` 均解析为命名模块，IoC 跨模块反射实例化 bean 正常。
- **待办**：其余 ~35 个模块的 module-info 逐模块补齐（`platform-*` / `transport-*` / `web` / `security` …）；
  建议补一个**常驻** module-path 回归测试（当前为临时消费者脚本验证）。

### 7.3 【中】Sonatype OSS 发布
`publishToMavenLocal` 已实测 40 个构件全部发布。**进一步**：
- 配 `gradle.properties` 中的 `sonatypeUsername/sonatypePassword`
- 改 `build.gradle.kts` 的 PublishingExtension 配 `ossrh-staging-api`：发布到 `https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/`
- 等 staging 仓库中转后再 release 到 Maven Central

### 7.4 【已完成 v1.0.2】grpc 真实集成
原计划 `InProcessServerBuilder`（需 grpc-core/grpc-stub/grpc-netty-shaded 且为测试依赖）。
v1.0.2 采用**更贴近生产**的方案：`GrpcTransportServer` 用 `NettyServerBuilder` 绑定随机端口（本机回环），
`GrpcTransportClient` 用 `NettyChannelBuilder` 连接；**不依赖 protobuf 代码生成** —— 以通用 unary
`MethodDescriptor` + `byte[]` 直通编组承载 JSON wire。`GrpcIntegrationTest` 3 例真实回环全绿。
新增依赖：`io.grpc:grpc-stub:1.62.2`、`io.grpc:grpc-netty:1.62.2`。

### 7.5 【已完成 v1.0.2】kafka/rmq/nats/mqtt 真实集成
4 个传输均已补真实实现 + `DockerContainer` + 集成测试：
- kafka（v1.0.1）`apache/kafka:3.7.0`；rmq `rabbitmq:3-management`；nats `nats:2.10-alpine`（或任意 nats-server）；mqtt `eclipse-mosquitto:1.6`（1.6 默认允许匿名远程连接；2.x 需挂载配置放开 listener）
- 地址优先级：`JVFAULT_*_BOOTSTRAP` 环境变量 → 本地 Docker 容器 → 跳过（不影响构建绿）

### 7.6 【低】Javadoc + 站点
`./gradlew javadoc` 能产出 javadoc；可加 `asciidoctor` plugin 产出完整站点（受网络限流时先在 CI 跑）。

### 7.7 【低】Spring Boot 启动器
v1.0.0 后可做 `jvfault-spring-boot-starter`（自动配置、@SpringBootApplication → @Module 桥接）。

## 8. 接手即用的下一步

**如果只做一件事**（推荐顺序）：

1. **跑一次全量验证**确认接手时状态：
   ```bash
   cd /Users/Wang/Code/webfault-lib/jvfault-all/jvfault
   JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew clean build
   ```
   期望 BUILD SUCCESSFUL，263 tests 0 failed（无 broker 时集成测试自动跳过，构建仍绿）。
2. **CI 镜像**（已完成 v1.0.2）：`.github/workflows/ci.yml` 已加
   redis / kafka / rabbitmq / nats / mosquitto service 与 `JVFAULT_*_BOOTSTRAP`；grpc 走本机回环无需 service。
3. **推进 v1.0.2 → v1.1.0** 的下一个特性：建议**`jvfault-spring-boot-starter`**（Spring 桥接，最大化 Java 生态覆盖）。

## 9. 联系方式

- 框架 GitHub：https://github.com/rwxbot/jvfault
- git 身份：johnnynode <johnnynode@gmail.com>
- 父 monorepo（workspace）：`/Users/Wang/Code/webfault-lib/jvfault-all/`

---

**本次会话归档完成。下一位 Agent 可从本工作区与本文档继续。**
