# jvfault Framework

> **Modern Modular Java Framework Architecture** — 注解驱动、模块化、类型安全的纯 Java 框架。
> 基线 **Java 17**（`--release 17` 编译，与 Spring Boot 3 / Jakarta EE 10 同款 floor），遵循 JSR-330 / JSR-250 / SPI 标准，仅依赖少量成熟主流 SDK。
> **注意**：虚拟线程模块需 JDK 21，AI 时代模块为 JDK 17 —— 详见下方「JDK 需求分层」。

[![Java](https://img.shields.io/badge/Java-17%2B-blue.svg)](https://openjdk.org/)
[![Gradle](https://img.shields.io/badge/Gradle-8.x-green.svg)](https://gradle.org/)
[![Tests](https://img.shields.io/badge/tests-309%20passing-brightgreen.svg)](#构建与测试)
[![CI](https://github.com/rwx-robot/jvfault/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/rwx-robot/jvfault/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Version](https://img.shields.io/badge/release-v1.0.16-blue.svg)](#)

**jvfault** 是一个以 Java 标准库实现的模块化应用框架：IoC 容器 + 模块化系统为内核，
上层覆盖 Web（Servlet/Reactive）、安全、合规、可观测性、微服务传输与 AI 接入。
设计风格借鉴主流注解驱动框架：**内核自研、外围能力直接复用业界事实标准 SDK**
（Jakarta Validation + Hibernate Validator、Caffeine 3.x、Jackson、Reactor 等），不引入 Spring 全家桶 / Kotlin。

| 维度 | 现状 |
|------|------|
| 模块 | 40 个（**39 个含测试**；`test` 模块暂无测试） |
| 版本示例 | 6 个（v0.8.0 → v1.0.0 每版本一个可运行示例，另加 `spring-boot-bridge` Spring Boot 3 双向桥接示例） |
| 测试 | **309 个，0 失败**（含 8 例 JPMS 多版本 JAR 回归；无 broker 环境下部分集成测试跳过，CI 全绿） |
| 传输适配 | tcp / grpc / kafka / redis / rmq / nats / mqtt（**全部真实集成**：broker 或 netty 回环） |
| JPMS | **38 个模块** 提供多版本 JAR 的 `META-INF/versions/9/module-info.class`（Java 17 基线、9+ 模块可用） |
| 构建 JVM | 必须用 JDK 21 作 Gradle JVM（`JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.0.11.jdk/Contents/Home`） |
| CI | GitHub Actions：5 个真实 broker（redis / kafka / rabbitmq / nats / mosquitto）+ gRPC 回环；18 个传输集成测试在 CI 上实跑 |

## 快速开始

```bash
# 构建全部 40 个模块 + 6 个版本示例（39 个模块含测试，共 309 个测试）
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.0.11.jdk/Contents/Home ./gradlew build

# 单模块
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.0.11.jdk/Contents/Home ./gradlew :core:test

# 运行示例（v0.8.0 - v1.0.0 每版本一个）
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.0.11.jdk/Contents/Home ./gradlew :examples:v1.0.0:run

# Maven 本地安装
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.0.11.jdk/Contents/Home ./gradlew publishToMavenLocal
```

## 核心概念

| 组件 | 说明 |
|------|------|
| `@Component` / `@Module` | Bean 与模块声明 |
| `@Inject` / `@Named` | JSR-330 注入 |
| `BeanRegistry` | 三级缓存循环依赖、FactoryBean、父子容器 |
| `BeanPostProcessor` | 初始化序列扩展点（AOP/配置/调度接入点） |
| `WebApplication` | 路由 → Guard → 拦截器 → RFC 7807 |

## 模块总览

```
core        IoC 容器、模块系统、类路径扫描、引导
aop         @Aspect 切面、execution pointcut、JDK/ByteBuddy 代理
config      Environment、YAML/Properties/JSON、@Value/@ConfigurationProperties
validation  Jakarta Validation + Hibernate Validator 约束校验（级联 + 自定义校验器 SPI）
exception   RFC 7807 ProblemDetail、@ExceptionHandler 注册表
logging     结构化 JSON 日志、MDC trace 透传、动态级别
web         @Controller 路由、Guard/拦截器管道、参数绑定
platform-servlet   Servlet 5.0 桥接
platform-reactive  Reactor Mono/Flux 渲染
websocket   @WebSocketGateway 网关与房间广播
sse         规范事件流 SseEmitter / SseHub
openapi     OpenAPI 3.0 文档生成
graphql     graphql-java schema-first 端点
security    JWT HS256、PBKDF2 口令哈希、JwtGuard
microservices      Transport SPI、@MessageHandler、客户端代理
transport-{tcp,grpc,kafka,redis,nats,rmq,mqtt}   传输适配（7 个均含真实集成测试）
tracing     W3C traceparent、Span/Tracer
metrics     Counter/Timer/Gauge/Summary
scheduling  @Scheduled + 6 字段 Cron
cache       @Cacheable/@CacheEvict、多级缓存（Caffeine 3.x）
plugin      插件 SPI：隔离 ClassLoader、依赖拓扑、生命周期
apt         编译期 @Module 元数据生成（META-INF/jvfault/modules.txt）
aot/native  GraalVM 反射配置生成与 native 提示
ai/rag/mcp              ChatModel SPI、向量检索、MCP 服务器                 (JDK 17)
compliance/migration    合规审计脱敏、迁移分析                               (JDK 17)
ops                     健康检查、指标端点、优雅关闭                          (JDK 17)
virtualthreads          虚拟线程执行器与结构化并发                            (JDK 21)
spring-boot-starter      Spring Boot 3 自动配置桥接（可选插件，不传递 Spring 依赖）   (JDK 21)
test        JUnit 5 扩展 (@TestModule + @Autowired)
tests       跨模块端到端套件（Jetty + JWT）
```

## JDK 需求分层

框架**默认**以 `--release 17` 编译（根 `build.gradle.kts`），与 **Spring Boot 3 / Jakarta EE 10** 同一 floor；
**虚拟线程模块**需要 JDK 21 —— 它们在各自的 `build.gradle.kts` 里覆盖了 `options.release`：

| 需求 JDK | 模块 | 依据 |
|:--------:|------|------|
| **17**（默认） | core、web、security、logging、metrics、transport-\*、cache、scheduling、validation 等**其余全部** | 根 `build.gradle.kts` → `options.release = 17` |
| **9** | 仅 `module-info.java` 编译层（产物落在 MR-JAR 的 `META-INF/versions/9/`） | JPMS 描述符自 JDK 9 起可用 |
| **17** | `ai`、`rag`、`mcp`、`compliance`、`migration`、`tests` | 各自 `build.gradle.kts`（与默认一致） |
| **21** | `virtualthreads`、`spring-boot-starter`、`examples/v0.8.0` | 依赖 Project Loom 虚拟线程 API / Spring Boot 3（按 21 编译） |

> 全模块**聚合 Javadoc** 站点：`https://jvfault.github.io/jvfault/`（由 `.github/workflows/pages.yml`
> 在 push 到 main 且 Java/构建配置变更时自动构建并部署到 GitHub Pages）。
> 本地预览：`./gradlew javadocAggregate` → 产物在 `build/docs/javadoc-aggregate`。

### 对齐主流 SDK（别人用什么，我们就用什么）

框架内核坚持「纯 Java + 标准 API」的取向，但**依赖选型直接对齐主流 Java 框架的默认栈**，
不重复造轮子、不抱守 Java 8 时代的旧库：

| 能力 | 选型 | 说明 |
|------|------|------|
| 校验引擎 | **Jakarta Validation 3.0** + **Hibernate Validator 8** | `validation` 模块直接委托标准 API + 主流 Provider（Spring Boot 3 / Quarkus / Jakarta EE 同款），约束注解用 `jakarta.validation.constraints.*` |
| L2 缓存 | **Caffeine 3.1.x** | 与 Spring Boot 3 托管版本一致（要求 Java 11+，本框架 Java 17 基线满足） |
| 日志门面 | SLF4J 2.0.x | 主流默认 |
| JSON | Jackson 2.17.x | 主流默认 |
| 响应式 | Reactor 3.6.x | Spring WebFlux 同款 |
| GraphQL | graphql-java 21.5 | 社区主流 |
| AOP 字节码 | ByteBuddy 1.14.x | Spring / Mockito 同款 |

即：**内核自研**（IoC / 模块化 / 路由 / 传输），**外围能力直接复用业界事实标准 SDK**。
历史包袱说明：早期 `validation` 是自研的 JSR-380 子集（反射扫描 + 自研约束注解），
现已替换为标准 Jakarta Validation + Hibernate Validator；`cache` 的 Caffeine 也已从 2.9.3 升到 3.1.x。

### 构建与运行

> **构建必须使用 JDK 21**（Gradle JVM）。本仓库统一用：
> `JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.0.11.jdk/Contents/Home ./gradlew build`
> （注意：`/usr/libexec/java_home -v 21` 在部分 macOS 上会误解析到旧 JDK，导致编译报 Java 12 语法错误。）
> 构建期用 JDK 21 编译，产物以 `--release 17` 产出，**运行时需 Java 17+**；
> 引入 `virtualthreads` / `spring-boot-starter` 等模块时需 **Java 21** —— 与 Spring Boot 3 一致。

> **第三方依赖运行时下限**（非本框架字节码约束）：`openapi` 运行需 `snakeyaml 2.2`（Java 9+）、
> `graphql` 运行需 `graphql-java 21.5`（Java 11+）；二者均不高于基线 Java 17，无额外影响。

## 集成测试与 CI

传输模块的集成测试连接真实服务，地址来源优先级：

1. 环境变量 `JVFAULT_REDIS_BOOTSTRAP` / `JVFAULT_KAFKA_BOOTSTRAP` / `JVFAULT_RMQ_BOOTSTRAP`
   / `JVFAULT_NATS_BOOTSTRAP` / `JVFAULT_MQTT_BOOTSTRAP`（CI 由 GitHub Actions `services` 提供）
2. 本地 Docker 容器（`redis:7.2-alpine`、`apache/kafka:3.7.0` KRaft 单节点、`rabbitmq:3-management`、
   `nats:2.10-alpine`、`eclipse-mosquitto:1.6`）
3. 两者都不可用 → 该集成测试跳过（不影响构建绿）

环境变量指向的 broker 会先做 **TCP 可达性探测**（30s 重试窗口），不可达则跳过 ——
这样 CI service 容器处于「端口已映射但应用未就绪」的窗口期时不会把构建拖红。

CI 上 **5 个 broker 全部以真实 service 容器接入**（redis / kafka / rabbitmq / nats / mosquitto），
gRPC 走本机回环，因此 **18 个传输集成测试在 CI 上真实执行**（不再跳过）。
该数字由 `scripts/verify-transport-it-count.sh` 静态机检（与运行环境无关），增删集成测试须同步本文件的「18」与脚本默认值。
Kafka 的 service 健康检查必须写全路径 `/opt/kafka/bin/kafka-broker-api-versions.sh`
（该脚本不在镜像 PATH 上，写裸命令会让容器被判 unhealthy）。

### 本地复现 CI 环境

CI runner 是 **UTC**，本地通常是 UTC+8 —— 时间戳相关断言可能在本地过、CI 挂。
改完时间/日期相关代码后，用下面命令复验：

```bash
TZ=UTC ./gradlew clean build --no-daemon --no-build-cache
```

`--no-build-cache` 是为了避免缓存命中掩盖问题。

gRPC 无需外部服务：服务端以 `grpc-netty` 绑定随机端口（本机回环），集成测试直接与之通信。

## 工作区关系

本仓库是框架本体；上层 Agent 工作区（`jvfault-all`）只保留参考资料与运行状态，不再跟踪框架代码。

## License

Apache License 2.0 — see [LICENSE](LICENSE)
