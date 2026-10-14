# jvfault Framework Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).
每个版本标签对应 roadmap 中的模拟纪元。

> **关于提交日期（2026-09-25 重建）**：提交原本集中在少数几天，现已按
> **强波浪**模式回填 —— 每个提交日集中 1~4 次提交，提交日间隔 3/6/9/13 天（波峰波谷），
> 时间跨度覆盖 2025 下半年至 2026 年 3 月，版本标签连续排列（v0.1.0 → v1.0.13）。
> 这是**回填的提交日期**（`GIT_AUTHOR_DATE` / `GIT_COMMITTER_DATE`），并非真实开发时间；
> 重建前的原始历史保留在远程备份分支 `backup/pre-rewrite-20260925`。
>
> **v1.0.14 起改用真实日期**（不再回填）：v1.0.14 / v1.0.15 = 2026-10-01，v1.0.16 = 2026-10-13。
> 2026-10-13 已将 main 与全部 29 个 tag 强推到远端（tag 重定向经 TREE-SAME 验证无损）。

---

## [v1.0.16] - 阿里评审第二轮：全 tag 确定性体检 + 并发/可观测/Locale/废弃 API 修复（2026-10-13）

> 本轮由 `scripts/review/java_smell_scan.py`（21 条规则、零 LLM）扫 28 个 tag 建缺陷台账后逐项修复。
> **编译告警 24 → 0（清零）**，测试 309 → **310**。

### Fixed — 并发正确性
- **`RabbitMQTransportClient` 锁在非 final 可变字段 `publishChannel` 上**（P1）：
  改用专用 `private final Object publishLock`。原写法在 `connect()` 重连换 Channel 后
  两个线程会锁在不同对象上，互斥彻底失效，而 RabbitMQ `Channel` 本身非线程安全。
  **该缺陷自 v1.0.2 引入、潜伏 12 个 tag** 到 v1.0.15 才被发现。
- **`RabbitMQTransportClient.ensureConnected()` 的 check-then-act 竞态**（P1）：
  并发首调用会各建一次连接、产生两套 reply 队列，响应永远等不到（表现为请求超时）。
  方法改 `synchronized`，与 `connect()` / `close()` 锁域闭合。

### Fixed — 可观测性（消除静默失败）
- 三处**完全静默**的异常吞掉（无注释、无日志）：`ai` SSE 坏块解析、`JwtGuard` claims
  写入失败、`transport-redis` 回包解析失败 → 补 `log.warn`。
  保留原语义不变（跳过坏包继续流 / fail-closed / 跳过坏包继续监听），只加可观测痕迹。
- **`MigrationAnalyzer` 读不到文件时裸 `return;` 静默跳过**（P2）：
  报告会写成「发现 0 处」，让人误以为已迁移干净。改为收集跳过清单并在扫描结束后
  `log.warn` 汇总（单文件读不了不该中断整轮扫描，故保留跳过行为）。

### Fixed — 编译告警台账（`-Xlint:all`，24 → 0）
- **两处 `[this-escape]`**（P1，JDK 21 才有）：
  - `config/DefaultConfigEnvironment`：构造器里调 `setActiveProfiles`/`addAll` 等**可覆写**方法，
    在子类（或 AOP 代理子类）构造完成前就把 `this` 交出去 —— jvfault 的 AOP 用 ByteBuddy
    生成子类代理，代理字段在父类构造器跑完前还没初始化。
    修法：装配动作挪到静态辅助方法、在另一个实例上完成，结果经新增的
    `protected ConfigurableEnvironment(sources, profiles)` 构造器（不可覆写）交给父类。
  - `microservices/InMemoryTransport`：构造器里 `new Thread(this::drainEvents)` 让 `this`
    在构造完成前被另一对象捕获。线程改到 `bind()` 创建并做幂等（顺带修掉重复 `bind()`
    会二次 `start()` 抛 `IllegalThreadStateException`）；`close()` 加 null 守卫。
- `[cast]` 冗余强转 ×3（`DefaultBeanRegistry` ×2、`ProxyFactory` ×1）。
- `[unchecked]` 泛型 ×2：`JwtGuard` / `JsonPropertySource` 的 `readValue(x, Map.class)`
  改用 `TypeReference<Map<String,Object>>`；`ValidationException` 形参放宽为
  `Set<? extends ConstraintViolation<?>>`，消除调用点的双重强转。
- **上游废弃 API ×10**（P3）：Jackson `asText(String)`→`textValue()` ×10、
  Lettuce `setDefaultTimeout(Duration)`→`RedisURI.setTimeout()` ×2、
  GraphQL `Builder.context(Object)`→`graphQLContext(Map)` ×1。
  均按上游替代方案改，**未用 `@SuppressWarnings` 压制**。

### Fixed — 平台默认 Locale
- `openapi/OpenApiGenerator` 的 `getHttpMethod().toLowerCase()` → `Locale.ROOT`：
  OpenAPI 3.0 的 paths 键必须规范小写，土耳其语下 `OPTIONS`→`optıons`，下游工具认不出该 method。
- `core/BeanDefinition` 的 `parseScope` 里 `scope.toUpperCase()` → `Locale.ROOT`：
  实测 `"singleton".toUpperCase(tr_TR)` = `"SİNGLETON"`（i → İ）。
  **诚实说明**：目前三个作用域里只有 `SINGLETON` 含 i，而解析失败恰好也退回 `SINGLETON`，
  因此**暂时观察不到**错误行为；这是隐患提前排除，一旦新增含 i 且回退值不同的作用域就会静默降级。
- 新增**非空洞**回归测试 `OpenApiLocaleIndependenceTest`：先断言
  `"OPTIONS".toLowerCase() == "optıons"`（证明环境真会触发缺陷），再断言生成的键是 `options`；
  **对照实验**：撤掉修复后实测 `FAILED`，恢复后转绿。`finally` 还原 Locale。

### Changed
- 精简 6 个文件的冗长注释（只留一行要点），删除 `JsonWriter` 4 行自我辩解注释（该处代码未改动）。
- POM url 从不存在的 `github.com/jvfault/jvfault` → **`github.com/rwxbot/jvfault`**
  （`build.gradle.kts` + `distribution/build.gradle.kts`）。
- `transport-tcp` 的 `TcpMessageFraming` 移到 **test 源集**（`tryUnframe()` 只被测试用到，
  生产走 `TcpTransportServer` 自己的 `readInt()`+`readFully()`）；
  `MAX_FRAME` 挪到 `TcpWireMessage`（main 源集的共享位置）。
- `distribution` BOM 移除 `api(project(":tests"))` —— `tests` 是跨模块验收模块，不发布。

### 判定为「不修」并说明理由
- `JsonWriter` / `TraceContext` 的 `String.format("%04x"/"%02x")` 曾被规则命中。
  **实测证伪**：JDK 21 下 tr_TR / hi-IN-u-nu-deva / ar-SA-u-nu-arab 三种 Locale 都输出
  ASCII 十六进制，**不受 Locale 影响** → 判误报，不加无依据的 `Locale.ROOT`
  （否则代码里留下理由不成立的注释，比不修更糟）。规则已相应改精确（只认 `%d/%f/%e/%g/%a`）。

---

## [v1.0.15] - core 真正的 REQUEST 作用域 + NPE 守卫 + 注册碰撞防护（2026-10-01）

### Fixed
- **REQUEST 作用域宣称与实现不符**（此前实际等同单例，会跨请求串号）：
  core 改用 ThreadLocal 请求作用域栈实现「每请求一实例」；无活动作用域解析时 fail-fast
  （不再退化成单例串号）；退域触发 `@PreDestroy` / `destroyMethod`。
  桥接 `JvfaultBeanBridge.isSingleton` 改为 `registry.isSingleton`，REQUEST 不再被 Spring 当单例缓存。
- `JvfaultApplication.createContainer(cls, null, ...)` 会 NPE：`basePackages` 加 null 守卫，
  `rootModuleClass` 空校验抛 `IllegalArgumentException`。
- `DefaultBeanRegistry.registerSingleton` 重复注册静默覆盖：同名且**不同**实例重复注册抛
  `IllegalStateException`；同名**同**实例幂等放行。
- 单例创建「检查→创建→缓存」的 check-then-act 竞态：加原子锁；并纠正 Javadoc 三级缓存的错误描述。

### Changed
- 测试总数 300 → **308**（新增桥接边界用例：撞名 fail-fast / REQUEST 作用域现状 / 生命周期计数）。

---

## [v1.0.14] - 引擎现代化（基线 Java 8→17）+ spring-boot-starter 双向桥接（2026-10-01）

### Changed — 引擎现代化（对齐主流 SDK）
- **根基线 Java 8 → 17**：根 `build.gradle.kts` 的 `options.release` 由 `8` 改为 `17`，
  对齐 Spring Boot 3 / Jakarta EE 10 的 floor；虚拟线程模块仍按 21 编译。
  移除已无用的 `scripts/java8-runtime-smoke`（基线不再是 8）。
- **Caffeine 2.9.3 → 3.1.8**：`cache/build.gradle.kts`，与 Spring Boot 3 托管版本一致。
- **validation 引擎换血**：自研 JSR-380 子集反射引擎 →
  **Jakarta Validation 3.0.2 + Hibernate Validator 8.0.1 + Jakarta EL（expressly 5.0.0）**，
  即 Spring Boot 3 / Quarkus / Jakarta EE 同一套校验栈。删除自研 `constraint/*` 注解与反射引擎，
  约束注解改用 `jakarta.validation.constraints.*`；保留 `Validator` / `ValidationEngine` /
  `ConstraintViolation` / `ValidationException` 门面 API。
- README 去 Java 8 口径，新增「对齐主流 SDK」小节。

> 坑：Hibernate Validator 默认消息随 JVM locale 本地化（中文「不能为null」），
> validation 测试已固定 `Locale.ENGLISH`；自定义消息建议用命名占位符 `{min}`/`{max}`。

### Added — spring-boot-starter 双向桥接
- **反向注入（Spring → jvfault）**：`jvfault.import-spring-beans`（默认 false，显式 opt-in）
  + `@JvfaultComponent` 标记注解。必须在 `container.refresh()` **之前**登记
  （jvfault 的 `@Inject` 解析发生在 `initializeSingletons()`，即 refresh 第 5 步）。
- **正向暴露（jvfault → Spring）**：用「BeanDefinition + 委托 FactoryBean」，
  类型可见靠 `setTargetType`（**不能用 `setBeanClass(实际类型)`**，会 ClassCastException）。
- 示例工程 `examples/spring-boot-bridge`（Spring Boot 3.2.5），`web=NONE` 测试。

> 已知架构性限制：用户 bean 先于 autoconfig bean 实例化，jvfault bean 要等 autoconfig `@Bean`
> 执行时才登记，而 `preInstantiateSingletons()` 遍历的是 beanDefinitionNames **快照**。
> 消费方需用 `@DependsOn("jvfaultModuleContainer")` / `@Lazy` / `ObjectProvider`。

---

## [v1.0.13] - 把 CI 相关宣称也纳入机检（broker 数 / JDK 覆盖）（2026-09-25）

### Added
- **`ciBrokerCountMatchesReadme()`**（机检）：README「N 个 broker」↔ `ci.yml` 里 service 的实际个数。
  两边都派生，无需常量 —— CI 加减 broker 却没改文档时会红。
- **`ciJdkCoversHighestRelease()`**（机检）：CI 的 `java-version` 必须**覆盖**所有模块里最高的
  `options.release`。这是「构建用 JDK 21、产物仍 `--release 8」这条承诺成立的前提：
  若某天新增 release 25 的模块而 CI 还是 21，构建会直接失败 —— 现在会提前被这条抓住。

### Changed
- 测试总数 285 → **287**（新增 2 个机检）。

## [v1.0.12] - 再堵两个漂移口子：版本 badge 与 JPMS 回归例数（2026-09-25）

### Added
- **`versionBadgeMatchesGradleProperties()`**（机检）：README 的 Version badge 必须等于
  `gradle.properties` 里的 `jvfaultVersion`。**两边都从源码派生，不需要维护常量** ——
  因此永远不会因「忘了改常量」而假红。
  动因：v1.0.11 时发现 Version badge 长期停在 `v1.0.8`，而实际版本已是 1.0.11
  （v1.0.9 / v1.0.10 **连续两次漏改**）。
- **`jpmsRegressionCountMatches()`**（机检）：README「含 N 例 JPMS 多版本 JAR 回归」的 N
  必须等于 `JpmsModulePathSmokeTest` 里 `@Test` 的实际个数。同样两边派生、无需常量。

### Fixed
- **JPMS 回归例数漂移**：README 写「含 6 例」，实际 `JpmsModulePathSmokeTest` 已有
  **8 个** `@Test`（`verifyModuleNameInSource` / `verifyMrJarDescriptors` /
  `verifyBuildVersionReadable` / `verifyClasspathIoC` / `verifyCurrentModuleReadable` /
  `verifyExamplesStructure` / `verifySpiServiceFilesReadable` / `verifyCoreModuleExports`）。
- 测试总数 283 → **285**（本次新增 2 个机检测试）。

## [v1.0.11] - 新增 spring-boot-starter：可选插件式 Spring Boot 3 桥接（2026-09-25）

### Added
- **`spring-boot-starter` 模块**：Spring Boot 3 自动配置桥接，把 jvfault 容器接入 Spring 生命周期。
  - `JvfaultProperties`（前缀 `jvfault`）：`base-packages`（**必填，opt-in**）、
    `root-module`（留空时自动推断 basePackages 下唯一的 `@Module`）、`exposeBeans`
  - `JvfaultAutoConfiguration`：创建 `ModuleContainer`，并把 jvfault 的 bean
    **单向暴露**为 Spring bean；`destroyMethod = "destroy"` 交由 Spring 托管生命周期
    （用 `createContainer` 而非 `run()` —— 后者会阻塞并注册自己的 JVM shutdown hook）
  - 通过 Boot 3 风格 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 注册
  - **刻意不做反向**（把 Spring bean 塞进 jvfault）：那会把两个容器的生命周期与依赖解析纠缠，
    循环依赖极难诊断；需要时由使用者显式桥接
  - 2 个回归测试：「配置后能从 Spring 拿到 jvfault bean」+「未配置时完全不激活」

### 插件化边界（Ny 要求「模块化、插件化」，逐条落实）
- 依赖方向**单向**：`spring-boot-starter -> core`；**core 不反向依赖本模块，也不依赖 Spring**，
  「零 Spring」承诺不受影响
- Spring 依赖声明为 `compileOnly`，**不会传递**进使用者的依赖树 —— 不引入本 starter 就完全没有 Spring
- opt-in：不配置 `jvfault.base-packages` 时自动配置不激活，放进依赖树零副作用
- 编译目标 **JDK 21**（当前 JDK；Spring Boot 3 要求 17+）
- 不提供 `module-info.java`：Spring Boot 自动配置并非 JPMS 友好，按 classpath 适配器发布
  （与 `tests` 一样不进 MR-JAR）

### Changed
- 各计数随之更新：框架模块 39 → **40**、含测试模块 39 → **40**、测试总数 281 → **283**、
  高 JDK 运行时模块 6 → **7**、运行时验收 jar 37 → **38**（通过数仍为 31）
- `ReadmeFactsConsistencyTest` 中两处写死的 `"39"` / `"7"` 改为引用常量 —— 加模块时不会再假红

### Fixed
- README 的 Version badge 一直停留在 `v1.0.8`（v1.0.9 / v1.0.10 连续两次漏改），本次同步为 v1.0.11

## [v1.0.10] - ponytail 审计收口 + JDK 21 引用修正 + 测试总数重定基线（2026-09-24）

### Removed
- **删除 7 个历史版本示例模块** `examples/v0.1.0` ~ `v0.7.0`（ponytail `mass`）：
  每个 3-15MB、展示价值最低，却让每次全量构建都跑一遍。保留 `v0.8.0`（最小，可作轻量参考）
  与 `v0.9.0`~`v1.0.0`（含最新版完整演示）；**版本示例数 12 → 5**。
- **删除 `MeterRegistry` 投机性接口**（ponytail `yagni`）：唯一实现为 `DefaultMeterRegistry`，
  且所有调用方都直接引用实现类 —— 注释里"保留桥接可能"从未兑现。
  接口删除后 `DefaultMeterRegistry` 降级为普通类（去掉 `implements` 与全部 `@Override`）。

### Fixed
- **JDK 21 引用修正**：README / 快速开始里的 `JAVA_HOME=$(/usr/libexec/java_home -v 21)`
  在部分 macOS 上会**误解析到 Java 12**（`tests` 模块要求 `--release 17`，会报具有误导性的
  switch 表达式 / record 编译错误）。现统一改为绝对路径
  `/Library/Java/JavaVirtualMachines/jdk-21.0.11.jdk/Contents/Home`，并写明：
  **构建用 JDK 21，产物仍以 `--release 8` 编译，运行时向下兼容 Java 8** —— 二者不矛盾。
- **测试总数重定基线 274 → 281**：v1.0.9 新增 `ReadmeFactsConsistencyTest`（7 例）时漏算自身，
  导致 README 写的 274 而真实已是 281。现按全量实际运行总数校正（badge / 维度表 / 快速开始三处）。
  ⚠️ 测试总数是**唯一无法自我校验**的宣称（由 Gradle 产出，测试自身看不到），
  已在 `ReadmeFactsConsistencyTest.EXPECTED_TEST_COUNT` 处写明「改测试必须跑全量并重算」。

### Changed
- `settings.gradle.kts`：示例清单移除 v0.1.0~v0.7.0（剩 5 个）。
- `JpmsModulePathSmokeTest.verifyExamplesStructure()`：期望示例数 12 → 5。
- `ReadmeFactsConsistencyTest`：示例数常量 12 → 5、测试总数 274 → 281。

### 审计建议未采纳（附理由）
- `LogEntry.Builder` 转 `record`：**不采纳** —— 主代码基线 `--release 8`，record 需 14+，
  强行升级会破坏「向下兼容 Java 8」这条对外承诺；且该 builder 本身并非过度设计。
- `DefaultMeterRegistry.toTags()` 改 stream：**不采纳** —— 现有循环已是 Java 8 下最简写法，
  换成 `IntStream` 反而更啰嗦。

## [v1.0.9] - 文档宣称数字化为机器校验：README 数字漂移一次性堵死（2026-09-24）

### Fixed
- **docs（数字漂移）**：修正 README 中随迭代悄悄过期的数字：
  - Tests badge 与维度表 `269` → **274**（自 v1.0.8 起测试总数已是 274）
  - 维度表「模块」行 `38 个含测试` → **全部 39 个均含测试**（实际 39/39 模块都有 `src/test/java`）
  - 快速开始段落 `38 个模块含测试，共 269 个测试` → `39 个模块均含测试，共 274 个测试`
  - 维度表「测试」行去掉「0 跳过」硬断言（skip 数随本地 broker 浮动，非回归信号）
- **docs（口径统一）**：README 内部口径对齐真实事实——框架模块 **39**、版本示例 **12**、传输适配 **7**、MR-JAR **38**、运行时验收 **37**（= 39 − `test` − `tests`）。

### Added
- **`ReadmeFactsConsistencyTest`**（tests 模块，7 个回归测试）——把「文档宣称漂移」这一类系统性问题
  一次性做成机器校验，与已有的 `BuildBaselineConsistencyTest`（JDK 分层防护）构成「代码事实 ↔ 文档宣称」双重防线：
  - 模块数 / 版本示例数 / 传输适配数 / 含测试模块数 / MR-JAR 数：全部从 `settings.gradle.kts` 与目录树
    **真实派生**，再与 README「维度」表比对（派生值 ≠ 常量，或 README ≠ 常量，均红）
  - 测试总数：badge 与维度表都必须写 **274**
  - 运行时冒烟数：高 JDK 运行时模块 **6** 个、通过 **31** + 抛错 **6** = 已发布 **37**，内部一致性强制校验
  - 同样**故意设摩擦**：改测试数 / 加模块 / 加传输适配时必须同步本文件常量 + README，否则 CI 立刻报警

## [v1.0.8] - 「Java 8 基线」文档与实现不符：6 个模块实际需 JDK 17/21（2026-09-24）

### Fixed
- **docs（宣称与运行时不符）**：README 长期宣称「基线 JDK 8」，但实测有 **6 个已发布模块**
  并不兼容 Java 8 —— 它们在各自 `build.gradle.kts` 里覆盖了 `options.release`：

  | 模块 | 实际 release | 原来 README 怎么标 |
  |------|:------------:|--------------------|
  | `ai`、`rag`、`mcp` | 17 | ✅ 标了 `(JDK 17)` |
  | `compliance`、`migration` | 17 | ❌ **漏标** |
  | `virtualthreads` | 21 | ❌ **完全没标** |

  用户按 README 选型，把 `virtualthreads` 拉进 Java 8 运行时会直接
  `UnsupportedClassVersionError`。现已补上完整标注。

### Added
- **README「JDK 需求分层」章节**：完整列出 8 / 9（仅 module-info 编译层）/ 17 / 21 四档，
  每档指明依据（`build.gradle.kts` 行号）。并写明**运行时实测结论**：
  37 个已发布 jar 在真 Java 8 上 31 个通过、6 个抛错（预期行为，非缺陷）。
- **`BuildBaselineConsistencyTest`**（tests 模块，3 个回归测试）——
  把「JDK 分层」这个隐性契约固化成机器可校验的规则，三重防护：
  1. 各模块实际 `options.release` 与期望表一致（偷偷升降基线即失败）
  2. 所有 release > 8 的**已发布**模块必须在 README 清单里标 `(JDK n)`（防止再次漏标）
  3. README 分层表与实际 release 双向吻合（含反向校验）

  这是**故意设的摩擦**：改动基线时必须同步 `EXPECTED` 表 + README 两处，避免只改一边。
- **`scripts/java8-runtime-smoke/`**：可复现的 Java 8 运行时验证脚本。
  自动下载 Temurin 8 JRE、编译冒烟程序（`--release 8`）、把全部 jar 丢到真 Java 8 上加载。
  运行：`./scripts/java8-runtime-smoke/run.sh`

### 备注
- 冒烟的已知限制：`platform-reactive`/`openapi`/`native`/`aot`/`graphql`/`sse`
  依赖 Spring、WebFlux 等三方库，脚本 classpath 只有 jvfault + slf4j，
  因此这些模块**未被严格验证**（脚本已把依赖缺失错误排除在「Java 8 不兼容」之外）。

---

## [v1.0.7] - Kafka 纳入 CI：5 个 broker 全覆盖，18 个集成测试全实跑（2026-09-23）

### Fixed
- **ci**：Kafka 进不了 CI 的真因 —— `kafka-broker-api-versions.sh` **不在容器 PATH 上**，
  位于 `/opt/kafka/bin/`。健康检查写裸命令 → `command not found` → 容器被判 unhealthy →
  GitHub `Initialize containers` 步直接失败，整轮 CI 挂。
  改为写全路径 `/opt/kafka/bin/kafka-broker-api-versions.sh`，
  且 `--bootstrap-server` 用**容器内监听地址** `localhost:9092`（不是宿主机映射端口）。
- **docs**：README 的 CI badge 是**静态假徽章**（`badge/CI-passing`），
  CI 真红时仍显示 passing。改为 GitHub Actions 实时徽章
  `.../actions/workflows/ci.yml/badge.svg?branch=main`。
- **docs**：README 集成测试数误写 28，实为 **18**（6 个传输模块 × 3）；删除重复的 badge 块。

### Added
- **ci**：Kafka service 回归（redis / kafka / rabbitmq / nats / mosquitto 五个真实 broker）。
  CI 上 **18 个传输集成测试全部真实执行，不再跳过**。
- **docs**：README 补「本地复现 CI 环境」指引 —— CI runner 是 UTC、本地多为 UTC+8，
  改完时间相关代码必须用 `TZ=UTC ./gradlew clean build --no-daemon --no-build-cache` 复验。
- **docs**：能力表补 CI 行。

### Changed
- **build**：构件版本 `jvfaultVersion` 由 `1.0.6` 升到 `1.0.7`。

### Verification
- 本机同镜像同参数实测 Kafka 健康检查：`docker inspect` → `health=healthy`（约 10s）
- `JVFAULT_KAFKA_BOOTSTRAP=localhost:9092 ./gradlew :transport-kafka:test`
  → 3 tests 0 skipped 0 failures
- **全量 CI 等价复现**（`TZ=UTC` + 5 broker + `--no-build-cache`）：
  **269 tests 0 failures 0 errors 0 skipped**，其中传输集成 **18/18 全跑**
- GitHub Actions 连续两轮 success

---

## [v1.0.6] - CI 首次全绿（修复 4 类"只在干净环境暴露"的缺陷）（2026-09-23）

> 本轮起 CI（GitHub Actions）连续为绿。此前所有失败都源于**只有干净环境才会触发**
> 的缺陷 —— 本地因构建产物/镜像/时区已就位而看不出来。

### Fixed
- **logging**：`PlainTextFormatterTest` 时间戳正则不接受 `Z` 偏移。
  `DateTimeFormatter` 的 `XXX` 在零偏移时按 ISO-8601 渲染为 `Z`（CI runner 为 UTC），
  非零偏移渲染为 `+08:00`（本机 UTC+8）→ 本地过、CI 挂。
  正则改为 `(?:[+-]\d{2}:?\d{2}|Z)`。
- **transport-\***：`DockerContainer.ensureImage()` 用「stdout 是否为空」判断镜像
  是否存在，但 `exec()` 合并了 stderr —— 镜像不存在时 docker 把
  `Error response from daemon: No such image` 写到 stdout，判空为 false →
  `hasImage()` 恒返回 true → 测试去 `docker run` 一个不存在的镜像 → 起不来 →
  空转 2 分钟后 `@BeforeAll` 抛错，整个测试类失败（CI runner 无镜像，必现）。
  改为**判退出码**：新增 `execCapture/execExitCode`；`start()` 亦校验 `docker run`
  退出码，若失败但端口已有 broker 在监听则复用（`containerId` 留 null，
  `close()` 不会误删外部容器）；`transport-redis` 的 `dockerAvailable()` 补显式判空。
- **transport-\***：拿到 `JVFAULT_*_BOOTSTRAP` 后直接 `brokerUp = true`，不做可达性
  探测。CI service 容器"端口已映射但应用未就绪"或本机未起 broker 时，
  测试真连 → `TransportException` → 整个类失败。
  新增 `isReachable(scheme://host:port)` TCP 探测（30s 重试窗口），不可达则
  `brokerUp = false` 交由 `assumeTrue` 跳过；Kafka 的 `waitForBroker` 由"抛错"改为
  "未就绪则跳过"。
- **tests**：`:tests:test` 未依赖全量模块 jar。`JpmsModulePathSmokeTest`
  遍历 38 个带 `module-info.java` 的模块校验各自的 jar，但 `:tests` 只声明了
  3 个依赖 → 干净检出（CI 每次都是）时其余 ~35 个 jar 尚未产出 → 失败。
  改为 `:tests:test.dependsOn(<每个子项目>:jar)`（任务路径字符串惰性解析）；
  并让 `verifyMrJarDescriptors` 在"发现 0 个模块"时明确失败，避免 `moduleRoot()`
  解析错时循环空转造成"假绿"。

### Added
- **ci**：新增 `release.yml` —— 推送 `v*` tag 时自动跑 `./gradlew jar`、从
  CHANGELOG 提取该版本章节作为 release notes、创建 GitHub Release 并附加全部
  构件（v1.0.5 已验证：40 个 jar 资产）。tag 含 `-rc/-alpha/-beta` 标记 prerelease。
- **ci**：`ci.yml` 增加 `concurrency`（同 ref 互斥，自动取消旧 run）、
  `permissions: contents: read`（最小权限）、`tags: ['v*']` 触发、
  JaCoCo 报告上传、`ubuntu-22.04` 固定版本。
- **build**：`check.dependsOn(jacocoTestReport)`，各模块构建时产出覆盖率 HTML 报告。

### Notes
- **CI broker 覆盖**：redis / rabbitmq / nats / mosquitto 以 GitHub service 容器接入；
  gRPC 走本机回环。Kafka 暂未纳入 CI（KRaft 冷启动 + 缺少可靠的 service health check
  会让 `Initialize containers` 误判失败），其集成路径由本地 + Docker 通道覆盖。
- 新增 skill `gradle-jpms-mrjar`：沉淀「Gradle 多模块 Java 8 基线 + JPMS 多版本 JAR」
  的做法与 6 个高频陷阱。

### Changed
- **build**：构件版本 `jvfaultVersion` 由 `1.0.5` 升到 `1.0.6`。

---

## [v1.0.5] - JPMS 回归测试加固（消除静默失败）（2026-09-23）

### Fixed
- **tests**: `JpmsModulePathSmokeTest` 三个静默失败
  - **版本硬编码 1.0.3**：通过 `tests/build.gradle.kts` 注入 `-Djvfault.framework.version`
    系统属性，避免在 1.0.4+ 上静默跳过所有 jar 校验
  - **模块列表硬编码**：改为 `Files.newDirectoryStream(<root>)` 自动发现所有
    `src/main/java9/module-info.java`，新增模块自动纳入
  - **jar 缺失静默跳过**：改为失败累加器，缺失则 fail
- **tests**: 新增 `verifyModuleNameInSource` —— 源码层校验
  `module-info.java` 里的 `module com.jvfault.X` 与项目自动模块名一致
  （捕获 `:native` 项目名是保留字之类的静默错误）
- **tests**: 新增 `verifyExamplesStructure` —— 校验 12 个 examples/v* 目录
  + 每个含 `Application.java`（防止 examples 目录被切走仍声称 12/12 绿）
- **build**: `tests/build.gradle.kts` 通过 `rootProject.projectDir.absolutePath`
  注入 `-Djvfault.project.root`，解决 `:tests:test` 的 `user.dir` 是 tests 模块目录
  而非项目根的问题

### Added
- **tests**: 常驻 JPMS 回归 6 例（267 → 269 测试）

### Changed
- **build**: 构件版本 `jvfaultVersion` 由 `1.0.4` 升到 `1.0.5`

---

## [v1.0.4] - JPMS 全面覆盖（2026-09-23）

### Added
- **module-info**: 30 个新模块补 `src/main/java9/module-info.java`：
  `aop` / `apt` / `cache` / `compliance` / `config` / `graphql` / `logging` / `mcp` /
  `metrics` / `migration` / `microservices` / `ops` / `openapi` / `platform-reactive` /
  `platform-servlet` / `plugin` / `rag` / `scheduling` / `security` / `sse` / `test` /
  `tracing` / `transport-{tcp,grpc,kafka,redis,rmq,nats,mqtt}` / `validation` /
  `virtualthreads` / `web` / `websocket` / `ai` — **合计 38 个 MR-JAR 模块**（剩余 2 个
  `tests`/`examples` 为非业务构件，无 MR-JAR 必要）
- **build/JPMS 改进**：根 `build.gradle.kts` 解析上游项目 jar 任务作为 module path（不依赖
  classes 目录）、新增独立 `compileJava9ModulePath` configuration 仅用于解析三方 jar；
  `:native` 模块名用硬编码特例（项目名是 Java 保留字）；`:security` 仅导出子包
- **tests**: 新增 `JpmsModulePathSmokeTest`（4 例），常驻回归：
  - MR-JAR 描述符完整（30 个核心模块 manifest + `META-INF/versions/9/module-info.class`）
  - `JvfaultApplication.getVersion()` 在 classpath / module path 下都可读
  - 当前测试 Module 标识可读（命名 / 未命名均可）

### Changed
- **build**: 构件版本 `jvfaultVersion` 由 `1.0.3` 升到 `1.0.4`

### Added
- **build/JPMS**: 多版本 JAR（MR-JAR）机制 —— 根 `build.gradle.kts` 在模块提供
  `src/main/java9/module-info.java` 时，以 `--release 9` **单独**编译该描述符
  （`--patch-module` 打补丁 + 依赖以 JAR 形态作为 module path），产物放入
  `META-INF/versions/9/` 并置 `Multi-Release: true`；**主代码仍以 `--release 8` 编译**
- **module-info**: 首批 4 个模块 —— `com.jvfault.core` / `.exception` / `.logging` / `.metrics`；
  `api` 依赖映射为 `requires transitive`，项目依赖以 JAR 上 module path

### 说明
- 同一构件**双向兼容**：Java 8 走 `Automatic-Module-Name`，Java 9+ 走
  `META-INF/versions/9/module-info.class`
- 已用**模块化消费者**实测：`requires com.jvfault.core` + `com.jvfault.exception` 的独立模块
  可在 module path 上编译并运行，IoC 容器跨模块反射实例化 `@Component` 正常
- 主类字节码 major 52（Java 8），描述符 major 53（Java 9）

### Changed
- **build**: 构件版本 `jvfaultVersion` 由 `1.0.2` 升到 `1.0.3`

---

## [v1.0.2] - 传输层真实集成补全 + JPMS 第一步（2026-09-22）

### Added
- **transport-rmq**: RabbitMQ 真实实现（amqp-client 5.20.0）—— topic exchange 按 pattern 绑定、
  `replyTo`/`correlationId` 请求-响应、handler 异常经 error 头回传；新增 `RabbitMQWire`、
  `DockerContainer` + `RabbitMQIntegrationTest`（3 例真实 broker）
- **transport-nats**: NATS 真实实现（jnats 2.17.5）—— NATS core request/reply（库自带 inbox）、
  `#` → `>` 通配翻译；新增 `NATSWire`、`DockerContainer` + `NATSIntegrationTest`（3 例真实 broker）
- **transport-mqtt**: MQTT 真实实现（Eclipse Paho mqttv3 1.2.5）—— 独占 replyTopic + correlationId
  请求-响应、`*` → `+` 通配翻译；新增 `MqttWire`、`DockerContainer` + `MQTTIntegrationTest`（3 例真实 broker）
- **transport-grpc**: gRPC 真实实现（grpc-netty 1.62.2）—— 通用 unary `MethodDescriptor` + `byte[]`
  直通编组（无需 protobuf 代码生成）；新增 `GrpcWire`/`GrpcMethod`、`GrpcIntegrationTest`（3 例，netty 本机回环）
- **ci**: 新增 rabbitmq / nats / mosquitto 三个 `services`，以及
  `JVFAULT_RMQ_BOOTSTRAP` / `JVFAULT_NATS_BOOTSTRAP` / `JVFAULT_MQTT_BOOTSTRAP` 环境变量
- **build/JPMS**: 40 个主构件写入 `Automatic-Module-Name: com.jvfault.<name>`（Java 8 兼容的
  JPMS 第一步）；`module-info.java` 与 `--release 8` 基线冲突，留待多版本 JAR 决策

### Changed
- **transport**: rmq / nats / mqtt / grpc 由「配置校验 + 惰性生命周期 + 客户端库适配骨架」升级为
  **真实实现**（真实 broker / netty 回环端到端验证），测试数 251 → 263
- **build**: 构件版本 `jvfaultVersion` 由 `1.0.1` 升到 `1.0.2`

### Fixed
- README / HANDOVER 数据同步：传输适配 7 个全部真实集成、263 测试

---

## [v1.0.1] - 示例闭环与集成测试真实性（2026-09-22）

### Added
- **examples**: 补齐 `v0.9.0`（plugin 生命周期 / apt 编译期元数据 / aot 反射注册）与 `v1.0.0`（security / compliance / migration / ops）两个可运行示例，版本示例闭环 12/12
- **transport-kafka**: `KafkaIntegrationTest` 真实 broker 集成 3 例（request-reply、error 头回传、事件 publish），支持 CI service 与本地容器双通道
- **ci**: GitHub Actions `services` 提供 redis 7.2 与 kafka 3.7（KRaft 单节点）；新增 `publishToMavenLocal` 校验步骤与测试报告上传
- **apt**: 新增真实 javac 编译测试，验证 `META-INF/jvfault/modules.txt` 生成与处理器 SPI 注册

### Fixed
- **build**: 构件版本 `jvfaultVersion` 由 `0.1.0` 升到 `1.0.1` —— 此前发布的 40 个构件版本与 git tag / roadmap 完全脱节
- **core**: `JvfaultApplication.getVersion()` 兜底值不再硬编码 `0.1.0`，改为读取构建期生成的 `META-INF/jvfault-build.properties`
- **apt**: 补 `META-INF/services/javax.annotation.processing.Processor` 注册 —— 此前消费者挂 `annotationProcessor` 时处理器从未被 javac 发现
- **transport-kafka**: `close()` 改由轮询线程关闭 `KafkaConsumer`（此前跨线程关闭抛 `ConcurrentModificationException`）
- **transport-kafka**: 无任何订阅时 `bind()` 不再调用 `consumer.poll()`（此前抛 `IllegalStateException`）

### Changed
- README / HANDOVER 数据同步：39 个模块、12 个版本示例、251 测试；移除已删除的 distribution BOM 描述

---

## [v1.0.0] - Production Ready（纪元 2026）

### Added
- **security**: JWT HS256（JDK JCE 实现零依赖）、PBKDF2 口令哈希、`JwtGuard` 角色守卫
- **ops**: `HealthIndicator`/`HealthAggregator`（liveness/readiness 探针语义）、`GracefulShutdown`
- **compliance**: 只追加 JSONL `AuditTrail`、`DataMasker`（手机号/邮箱/通用掩码）
- **migration**: Spring → jvfault 重写规则表、源码扫描器、Markdown 迁移报告
- **distribution**: java-platform BOM 对齐全部构件版本（后按 ADR-009 移除，改由 version catalog 对齐）
- **example**: v1.0.0 生产就绪全家桶演示（示例工程于 v1.0.1 补齐）

### 备注
- compliance/migration 为 2026 纪元模块，编译目标 JDK 17

---

## [v0.11.0] - AI Native（纪元 2025）

### Added
- **ai**: `ChatModel` SPI（函数式）、OpenAI 兼容客户端（JDK HttpClient + SSE 流式）、工具调用 schema
- **rag**: `VectorStore` SPI、内存向量存储（余弦相似度）、句子感知分块器、`RagPipeline`
- **mcp**: Model Context Protocol JSON-RPC 服务器（initialize/tools/resources）
- AI 时代模块编译目标 JDK 17

---

## [v0.10.0] - Native & Structured Logging（纪元 2024）

### Added
- **logging**: `LogEntry`（MDC trace 透传）、JSON/PlainText 格式化器、`StructuredLogger`（先拦截后格式化）、运行时级别注册表
- **native**: `NativeRuntimeHints`（reflect-config 聚合、native 模式探测）

---

## [v0.9.0] - Plugin & Compile-time（纪元 2023）

### Added
- **plugin**: ServiceLoader 发现、URLClassLoader 隔离、依赖拓扑排序、状态机生命周期
- **apt**: 编译期模块元数据注解处理器（META-INF/jvfault/modules.txt）
- **aot**: GraalVM reflect-config 生成器

---

## [v0.6.0] - Microservices（纪元 2020）

### Added
- **microservices**: `Message` 信封 + JSON 编解码、`PatternMatcher`（*/# 通配）、
  Transport SPI、`InMemoryTransport`、注解处理器（@MessageHandler/@EventPattern）、客户端代理工厂
- **transport-tcp**: JDK 完整实现（4 字节帧协议 + 端到端测试）
- **transport-{grpc,kafka,redis,nats,rmq,mqtt}**: 配置校验 + 惰性生命周期 + 客户端库适配骨架

---

## [v0.7.0] - API & Observability（纪元 2021）

### Added
- **openapi**: 从路由表生成 OpenAPI 3.0 文档（JSON/YAML，路径参数转换）
- **graphql**: schema-first 端点（graphql-java 21）
- **scheduling**: `@Scheduled`（fixedDelay/fixedRate/cron）、6 字段 Cron 解析器（含英文缩写、
  标准 DOM/DOW 通配语义）、线程池调度器 + 重臂链
- **cache**: `Cacheable/@CachePut/@CacheEvict`（JDK 代理）、ConcurrentMap/Caffeine/多级缓存

---

## [v0.2.0 ~ v0.5.0] - AOP, Config, Web Stack（纪元 2016-2019）

### Added
- **aop**: `@Aspect` + 通知注解、execution pointcut 子集、JDK/ByteBuddy 双代理
- **config**: `Environment` 抽象、YAML/Properties/JSON 源、`@Value`/`@ConfigurationProperties`/`@Profile`
- **exception**: RFC 7807 `ProblemDetail`、HTTP 异常层次、`@ExceptionHandler` 注册表
- **validation**: JSR-380 风格约束子集、级联校验、自定义校验器 SPI
- **web**: `@Controller` + 动词注解、路由（:param/*/字面优先）、Guard/拦截器管道、参数绑定
- **platform-servlet**: Servlet 5.0 桥接（请求/响应 + 分发 Servlet）
- **websocket**: `@WebSocketGateway`/`@SubscribeMessage`、房间广播
- **sse**: 规范事件流 `SseEmitter` + 通道 Hub
- **tracing**: W3C traceparent 传播、Span/Tracer、导出器 SPI
- **metrics**: Counter/Timer/Gauge/Summary、MeterFilter、JSON 快照

---

## [v0.1.0] - Foundation（纪元 2015）

### Added
- **core**: `DefaultBeanRegistry`（单例/原型/请求作用域、三级缓存循环依赖、FactoryBean、
  父子容器）、`ModuleContainer`（模块图 + 拓扑排序）、`ModuleScanner`（ClassGraph）、
  `JvfaultApplication` 引导、`BeanPostProcessor` 扩展点
- **test**: JUnit 5 扩展（`@TestModule` + `@Autowired` 字段/参数注入）
- **示例**: v0.1.0 核心应用

### Technical
- JDK 8 基线（`--release 8` 编译）
- ClassGraph 4.8.168、jakarta.inject 2.0.1、jakarta.annotation 2.1.1、SLF4J 2.0
- 构建零外部插件（纯 Gradle 内置 java-library/maven-publish/jacoco）

---

## 工程决策记录

- 2026-09-19: 全框架构建验证通过（36 个测试模块 239 个测试全绿，全部 10 个示例可运行）
- 2026-09-19: 框架从 monorepo 拆分为独立仓库（rwx-robot/jvfault）
- 2026-09-19: 构建"纯 Java 化" — 移除 Kotlin/Shadow/Checkstyle 插件依赖（ADR-001）
- 2026-09-19: transport-tcp 采用 JDK NIO 而非 Netty（ADR-007，零依赖基线）
