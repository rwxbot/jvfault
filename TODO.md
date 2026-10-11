# jvfault TODO — 剩余工作清单

> 最后更新：2026-10-13（对应 **v1.0.16 · 全 tag 缺陷体检第二轮（并发 + 可观测性）**）
> 状态口径：✅ 已完成 / ⏸ 待 Ny 决策或外部凭据 / 🔧 技术债（可自主推进）

---

## 一、待办总览

| # | 优先级 | 事项 | 状态 | 阻塞点 |
|---|:------:|------|:----:|--------|
| 1 | **P1** | Sonatype OSS 发布（Maven Central） | ⏸ | 缺凭据 |
| 2 | **P1** | spring-boot-starter：反向注入（Spring→jvfault） | ✅ | 本轮落地（`@JvfaultComponent` + `jvfault.import-spring-beans`，默认关） |
| 3 | **P1** | spring-boot-starter：示例工程 | ✅ | 本轮落地（`examples/spring-boot-bridge`，可运行 + web=NONE 测试） |
| 4 | **P2** | `distribution` BOM 模块缺失 | ✅ | 已落地（commit cfee7dd） |
| 5 | **P2** | Java 8 冒烟未能覆盖 6 个模块 | ✅ | 已做字节码级验证（见下） |
| 6 | **P2** | Javadoc 站点 | ✅ | 聚合站点 + Pages 部署（commit c1337ff） |
| 7 | **P2** | 「18 个传输集成测试」仍未被机检 | ✅ | 已加 scripts/verify-transport-it-count.sh（commit f70c39b） |
| 8 | — | spring-boot-starter 无 `module-info` | ✅ 刻意 | 见下方说明 |
| 10 | **P2** | `REQUEST` 作用域宣称与实现不符（实际等同单例，会跨请求串号） | ✅ | **v1.0.15 已修**：core 用 ThreadLocal 请求作用域栈实现「每请求一实例」；无活动作用域解析时 fail-fast（不再退化成单例串号）；退域触发 `@PreDestroy`/`destroyMethod`；桥接 `JvfaultBeanBridge.isSingleton` 改为 `registry.isSingleton`，REQUEST 不再被 Spring 当单例缓存 |
| 11 | **P2** | Spring bean 名与 jvfault 自有 `@Component` 撞名 → 组件静默消失（I1） | ⏸ | **仍 defer**：需 core 支持「外部预登记 vs 自有」区分（Origin/role），等架构师定 API；v1.0.14 起已识别，本轮未做（避免单方面改 core 公开语义） |
| 12 | **P3** | `JvfaultApplication.createContainer(cls, null, ...)` 会 NPE（S2） | ✅ | **v1.0.15 已修**：`basePackages` 加 null 守卫（`(basePackages != null && length>0)`），`rootModuleClass` 空校验抛 `IllegalArgumentException` |
| 13 | **P3** | `DefaultBeanRegistry.registerSingleton` 重复注册静默覆盖（纵深防御） | ✅ | **v1.0.15 已修**：同名且<b>不同</b>实例重复注册抛 `IllegalStateException`；同名<b>同</b>实例幂等放行 |
| 14 | **P1** | `RabbitMQTransportClient` 把锁加在非 final 可变字段 `publishChannel` 上（阿里评审；**v1.0.2 起潜伏 12 个 tag**） | ✅ | **v1.0.16 已修**：改用专用 `private final Object publishLock`。原写法在 `connect()` 重连换 Channel 后两个线程会锁在不同对象上，互斥彻底失效，而 RabbitMQ `Channel` 本身非线程安全 |
| 15 | **P1** | `RabbitMQTransportClient.ensureConnected()` 的 check-then-act 竞态（并发首调用会各建一次连接，产生两套 reply 队列，响应永远等不到 → 表现为请求超时） | ✅ | **v1.0.16 已修**：方法改 `synchronized`，与 `connect()` / `close()` 的锁域闭合 |
| 16 | **P1** | 三处<b>完全静默</b>的异常吞掉：ai SSE 坏块、`JwtGuard` claims 写入失败、redis 回包解析失败——均无注释、无日志 | ✅ | **v1.0.16 已修**：保留「跳过坏包继续」/ fail-closed 语义不变，补 `log.warn` 留可观测痕迹 |
| 17 | **P2** | `MigrationAnalyzer` 读不到文件时裸 `return;` 静默跳过，报告会写成「发现 0 处」，让人误以为已迁移干净 | ✅ | **v1.0.16 已修**：收集跳过清单，扫描结束后 `log.warn` 汇总（跳过行为本身保留——单文件读不了不该中断整轮扫描） |
| 18 | **P3** | `transport-tcp` 的 `TcpMessageFraming.tryUnframe()` **只被测试用到**（生产走 `TcpTransportServer` 自己的 `readInt()`+`readFully()`，只共用 `MAX_FRAME`），却住在 main 源集 | ⏸ | 移到 test 源集会动到测试基线，等 Ny 拍板；已在评审报告记录 |
| 19 | **P1** | `DefaultConfigEnvironment` 构造器调用可覆写方法（JDK 21 `[this-escape]`）：AOP 用 ByteBuddy 生成子类代理，代理字段在父类构造器跑完前未初始化，此时触发拦截器会看到半成品对象 | ✅ | **v1.0.16 已修**：装配动作挪到静态辅助方法、在另一个 `ConfigurableEnvironment` 实例上完成，结果经新增的 `protected ConfigurableEnvironment(sources, profiles)` 构造器（构造器不可覆写）交给父类 |
| 20 | **P1** | `InMemoryTransport` 构造器里 `new Thread(this::drainEvents)` 让 `this` 在构造完成前逃逸 | ✅ | **v1.0.16 已修**：线程改到 `bind()` 创建并做幂等（顺带修掉重复 `bind()` 会二次 `start()` 抛 `IllegalThreadStateException`）；`close()` 加 null 守卫 |
| 21 | **P3** | 编译期 `-Xlint:all` 告警 24 条 | ✅ 部分 | **v1.0.16**：`[this-escape]`×2、`[cast]`×3、`poll(long)` 废弃、`[unchecked]`×2 已清零 → 剩 10 条，全部是**上游库废弃 API**（Jackson `asText(String)`×7、Lettuce `setDefaultTimeout(Duration)`×2、GraphQL `Builder.context(Object)`×1），需按上游替代方案决策，不盲改 |

---

## 二、明细

### 1️⃣ Sonatype OSS 发布（P1 · ⏸ 缺凭据）

**现状**：`./gradlew publishToMavenLocal` 已验证可用；`maven-publish` 已为全部发布模块配置好 `pom`（名称/描述/Apache-2.0）。
**阻塞 / 待 Ny 提供的信息（填好后我负责补 `signing` + `maven-publish` 并跑 `./gradlew publish`）：**
- [ ] **Sonatype 账号**：`sonatypeUsername` / `sonatypePassword`（旧 OSSRH `issues.sonatype.org` 或新 `central.sonatype.com` 账号）
- [ ] **groupId 批准**：`com.jvfault` 这个命名空间需在 OSSRH 首次发布前申请并获批
- [ ] **GPG 签名**：Maven Central 强制签名。需 GPG 私钥 + 公钥传到 keyserver；提供 `signing.keyId` / `signing.password` / `signing.secretKeyRingFile`（或改用 `signingInMemoryPgpKeys` 内联）
- [ ] **staging 目标**：旧 OSSRH 用 `https://oss.sonatype.org/service/local/staging/deploy/maven2/`（配 `ossrh-staging-api`）；新 Central Portal 用 Portal API + snapshot 仓库
- [ ] **POM 补全**：SCM 地址、开发者信息、CI 自动发布开关（当前仓库缺，需补到 `gradle.properties` 或 `build.gradle.kts`）
- [ ] **本地已就绪**：`./gradlew publishToMavenLocal` 通过；`maven-publish` 已为全部发布模块配好 pom（名称/描述/Apache-2.0）
**验收**：
1. 配置凭据后 `./gradlew publish` 能推到 OSSRH staging
2. staging 仓库 close → release 后，Maven Central 上能搜到 `com.jvfault:core:1.0.13`
**备注**：这是「框架能被外部真正使用」的最后一步。在此之前使用者只能 clone 源码或装到本地 m2。

---

### 2️⃣ spring-boot-starter 反向注入（P1 · ✅ 本轮完成）

**已落地**：`jvfault.import-spring-beans`（**默认 false**，显式 opt-in）+ 新增 `@JvfaultComponent` 标记注解。
开启后，starter 会把标注 `@JvfaultComponent` 的 **Spring 单例**注册进 jvfault 的 `BeanRegistry`，
jvfault 的 `@Component` 即可用 `@Inject` 直接拿到 Spring bean。

**关键实现点（为什么这么写）**：
- **必须在 `container.refresh()` 之前注册** —— jvfault 的 `@Inject` 依赖解析发生在
  `initializeSingletons()`（refresh 第 5 步），那时 Spring bean 必须已在注册表里。
  为此 core 新增 `JvfaultApplication.createContainer(root, Consumer<BeanRegistry> beforeRefresh, basePackages)`
  重载（只用 JDK 的 `Consumer`，core 依旧零 Spring 依赖）。
- **只导入显式标注的 bean、且只导入 singleton 作用域** —— 避免倒灌整个 Spring 容器，
  规避 request/prototype 作用域在 jvfault 里无法托管的坑。
- 用 Spring 的**目标类型**（`getType`，而非 CGLIB 代理类）建索引，保证按接口/父类 `@Inject` 也能命中。

**验收**：starter 新增 2 个用例（`springBeansImportedIntoJvfault` / `reverseInjectionOffByDefault`），
示例工程端到端验证「Spring ClockService 反向注入 jvfault 且为同一实例」。

---

### 3️⃣ spring-boot-starter 示例工程（P1 · ✅ 本轮完成）

**已落地**：`examples/spring-boot-bridge`（Spring Boot 3.2.5 + `spring-boot-starter-web`）。

**结构**：`@SpringBootApplication` ExampleApplication + jvfault `@Module` DemoModule
+ jvfault `@Component` Greeter（`@Inject ClockService`）+ Spring `@Service @JvfaultComponent` ClockService
+ `@RestController` DemoController。

**一条请求串起两个容器**：Spring 请求 → jvfault Greeter → Spring ClockService → 响应。

**运行**：`./gradlew :examples:spring-boot-bridge:run` → `http://localhost:8080/greet?name=ny`
**测试**：`SpringBridgeExampleTest` 以 `web=NONE` 启动（不起 Tomcat，CI 友好）断言双向桥接。

**已知用法约束（已写进示例注释）**：jvfault bean 是自动配置运行时才注册的**动态单例**，
而用户 bean 先于自动配置 bean 实例化 —— 直接注入会因候选未注册而失败，
消费方需用 `@Lazy`（或 `ObjectProvider`）。这是动态暴露 bean 的标准姿势。

---

### 4️⃣ `distribution` BOM 模块缺失（P2 · 🔧 可自主做）

**问题**：根 `build.gradle.kts` 里已有防御分支 `if (name == "distribution" ...) return@subprojects`
（注释写「BOM 配置在 `distribution/build.gradle.kts`」），但：

- `distribution/` 目录**不存在**
- `settings.gradle.kts` **没有** `include(":distribution")`

即 BOM 是**规划过但从未落地**的缺口。
**影响**：使用者无法用一行 BOM 锁定全部 40 个模块的版本，必须逐个指定。
**验收**：
1. 新增 `distribution/build.gradle.kts`（`java-platform`，constraints 覆盖全部发布模块）
2. `settings.gradle.kts` 加入 include
3. `publishToMavenLocal` 产出 `jvfault-distribution` BOM pom

---

### 5️⃣ Java 8 冒烟未覆盖 6 个模块（P2 · 🔧 可自主做）

**问题**：`platform-reactive` / `openapi` / `native` / `aot` / `graphql` / `sse` 这 6 个模块依赖
Spring、WebFlux 等三方库；冒烟脚本 classpath 只有 jvfault 自身 + slf4j，
因此它们的类会因依赖缺失加载失败。脚本已把这类错误排除在「Java 8 不兼容」之外，
但**意味着这 6 个实际未被严格验证**。
**验收**：把三方依赖加入冒烟 classpath，让这 6 个也被真 Java 8 验证；
或在 README 中明确标注「不支持 Java 8 运行时」（目前只说未验证，口径偏软）。

---

### 6️⃣ Javadoc 站点（P2 · 🔧）

**现状**：`./gradlew javadoc` 已可产出（全局配置了 `-Xdoclint:none -quiet`）。
**缺口**：没有聚合站点与发布渠道。
**验收**：用 asciidoctor 生成聚合站点，挂 GitHub Pages。

---

### 7️⃣ 「18 个传输集成测试」仍未被机检（P2 · 🔧 部分难校验）

**问题**：README 写「18 个传输集成测试在 CI 上真实执行」，这个数**没有**机检。
难处：它依赖 CI 上 broker 是否就绪 —— 本地无 broker 时这些用例是 skip 而非消失，
所以总数不变、无法用总数校验；需要单独统计「传输集成测试类的用例数」。
**验收**：加一条统计传输集成用例数的机检，或把该表述改为不写死数字。

---

### 8️⃣ spring-boot-starter 无 `module-info`（✅ 刻意，非缺陷）

Spring Boot 的自动配置并非 JPMS 友好；本模块按 classpath 适配器发布，
与 `tests` 一样不进 MR-JAR。等 Spring 侧模块名稳定后再评估。

---

### 9️⃣ 引擎现代化：对齐主流 Java 框架 SDK（P2 · ✅ 本轮完成）

用户要求「把引擎改过来，别人用什么 SDK 你给我用什么 SDK，不要再用 Java 8」——本轮落地：

- **根基线 Java 8 → 17**：根 `build.gradle.kts` 的 `options.release` 由 `8` 改为 `17`，
  对齐 **Spring Boot 3 / Jakarta EE 10** 的 floor；虚拟线程模块仍按 21 编译。
  全部 40 模块 + 5 示例 `./gradlew build -x test` 通过。
- **Caffeine 2.9.3 → 3.1.8**：`cache/build.gradle.kts`，与 Spring Boot 3 托管版本一致
  （要求 Java 11+，Java 17 基线满足）；`CaffeineCache` 用法 2.x→3.x 完全兼容。
- **validation 引擎换血**：自研的 JSR-380 子集反射引擎 →
  **Jakarta Validation 3.0.2 + Hibernate Validator 8.0.1 + Jakarta EL（expressly 5.0.0）**，
  即 Spring Boot 3 / Quarkus / Jakarta EE 同一套校验栈。删除自研 `constraint/*` 注解与反射引擎，
  约束注解改用 `jakarta.validation.constraints.*`；保留 `Validator` / `ValidationEngine` /
  `ConstraintViolation` / `ValidationException` 门面 API（已重写测试，12 个用例全绿）。
- **README 去 Java 8 口径**：去掉「默认基线 JDK 8」起头，新增「对齐主流 SDK」小节；
  移除已无用的 `scripts/java8-runtime-smoke`（基线不再是 8）。

> 说明：Hibernate Validator 默认消息会随 JVM locale 本地化（如中文「不能为null」），
> 框架测试已固定 `Locale.ENGLISH` 以保证断言稳定；自定义消息建议用命名占位符
> `{min}`/`{max}` 等，位置占位符 `{0}`（属性名）是否被插值取决于校验 Provider 配置。

---

## 三、已关闭（本轮完成，不再列入待办）

| 事项 | 完成于 |
|------|--------|
| ponytail 审计 5 项（1 已修、2 已删、2 论证后不采纳） | v1.0.10 |
| 删除 7 个历史示例模块（12 → 5） | v1.0.10 |
| 删除 `MeterRegistry` 投机性接口 | v1.0.10 |
| JDK 21 引用修正（绝对路径，写明向下兼容） | v1.0.10 |
| 测试总数漂移 → CI 脚本 `verify-readme-test-count.sh` | v1.0.10 |
| `spring-boot-starter`（模块化/插件化） | v1.0.11 |
| 版本 badge ↔ `gradle.properties` 机检 | v1.0.12 |
| JPMS 回归例数机检（并修正 6 → 8） | v1.0.12 |
| CI broker 数 / CI JDK 覆盖机检 | v1.0.13 |
| GitHub About 描述 | Ny 已手动完成 |
| `distribution` BOM 模块（40 模块版本对齐） | v1.0.13+（commit cfee7dd） |
| 传输集成测试数量机检（`scripts/verify-transport-it-count.sh`） | v1.0.13+（commit f70c39b） |
| Java 8 冒烟升级为逐模块字节码基线验证（覆盖原「6 未验证」模块） | v1.0.13+ |
| 聚合 Javadoc 站点任务 + GitHub Pages 自动部署（pages.yml） | v1.0.13+（commit c1337ff） |
| spring-boot-starter 双向桥接 + 可运行示例（反向注入 + 示例工程） | v1.0.14（`6a81f9b`/`5e9eefa`） |
| 核心 REQUEST 作用域真正按请求隔离 + `createContainer` NPE 守卫 + `registerSingleton` 碰撞防护 | v1.0.15 |
| 单例创建「检查→创建→缓存」加原子锁（修复 check-then-act 竞态）+ 纠正 Javadoc 三级缓存错误描述 | v1.0.15+（阿里评审 P1） |
| 全 28 tag 确定性缺陷体检 + RMQ 非 final 锁 / ensureConnected 竞态 / 三处静默吞异常 / MigrationAnalyzer 静默跳过 修复 | v1.0.16 |
| 编译期 `-Xlint:all` 台账化并修复两处 `[this-escape]`（构造期 this 逃逸）+ 冗余强转 + unchecked 泛型 | v1.0.16 |
