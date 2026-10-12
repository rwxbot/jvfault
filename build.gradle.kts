import org.gradle.jvm.toolchain.JavaLanguageVersion

/**
 * jvfault Framework — common build configuration for all framework modules.
 *
 * Policy decisions (see docs/architecture-decisions.md):
 *  - Pure Java: no Kotlin, no fat-jar/shadow, no external quality plugins.
 *  - Baseline target: Java 17 (--release 17) for all modules — the same floor as
 *    Spring Boot 3 / Jakarta EE 10; Loom-era modules opt up to --release 21.
 *  - Only build-in plugins: java-library + maven-publish (+jacoco).
 */

allprojects {
    group = "com.jvfault"
    version = project.property("jvfaultVersion") as String
}

// The BOM project is configured separately (java-platform cannot take java-library).
subprojects {
    // distribution 是 java-platform BOM（与 java-library 互斥），
    // 它有自己的 build 单独发布；根发布跳过。
    // :examples 是 settings 里的**容器项目**（只做 include 聚合），没有自己的 build 脚本；
    // 只写 path.startsWith(":examples:") 匹配不到它，于是它会被当成普通模块套上
    // java-library + maven-publish，产出一个**空 jar**并被发布 —— 必须一并排除。
    if (name == "distribution" || path == ":examples" || path.startsWith(":examples:")) return@subprojects

    apply(plugin = "java-library")
    apply(plugin = "maven-publish")
    apply(plugin = "jacoco")

    configure<BasePluginExtension> {
        archivesName = "jvfault-${project.name}"
    }

    // 让每个子模块在 check 时产出 jacocoTestReport（HTML 报告落到 build/reports/jacoco/）。
    // 不放进 test chain —— jacoco 仅作覆盖率可视化，不影响测试通过判定。
    tasks.named("check").configure {
        dependsOn("jacocoTestReport")
    }

    // JPMS：构件写入 Automatic-Module-Name（Java 8 侧）；
    // 若模块提供 src/main/java9/module-info.java，则再打**多版本 JAR**（Java 9+ 侧），
    // 描述符置于 META-INF/versions/9/、manifest 置 Multi-Release: true。
    // 主代码以 --release 17 编译（JPMS 描述符单独以 --release 9 放入多版本 JAR），
    // Java 17 基线与 JPMS 兼容性兼得，且对齐 Spring Boot 3 / Jakarta EE 10。
    // 模块名优先 ext.moduleName，其次按特殊表（项目名是保留字时），默认 com.jvfault.<name>
    val moduleName: String = run {
        val fromExtra = project.extra.properties["moduleName"] as String?
        if (fromExtra != null) return@run fromExtra
        when (project.name) {
            // 项目名是 Java 保留字或与包名不符的特殊情况
            "native" -> "com.jvfault.nativeimage"
            else -> "com.jvfault." + project.name.replace('-', '.')
        }
    }
    val jarTask = tasks.named<Jar>("jar")
    val moduleInfo9 = file("src/main/java9/module-info.java")
    jarTask.configure {
        manifest {
            attributes["Automatic-Module-Name"] = moduleName
            if (moduleInfo9.exists()) {
                attributes["Multi-Release"] = "true"
            }
        }
    }
    // 项目依赖在子项目脚本执行后才被加入 compileClasspath。
    // 故把 MR-JAR 注册逻辑延后到 afterEvaluate — 否则 upstreamJarTasks 为空。
    afterEvaluate {
    if (moduleInfo9.exists()) {
        val mainClasses = extensions.getByType<org.gradle.api.tasks.SourceSetContainer>()
            .getByName("main").output.classesDirs
        // 上游 project 依赖：取其 jar 任务输出（保证 jar 在编译 module-info 前已经写出，
        // 否则无模块描述符）。三方 jar（slf4j / caffeine 等）走自动模块名。
        val compileClasspath = configurations.getByName("compileClasspath")
        val allDeps = compileClasspath.allDependencies.toList()
        val upstreamJarTasks: List<org.gradle.api.tasks.bundling.Jar> = allDeps
            .filterIsInstance<org.gradle.api.artifacts.ProjectDependency>()
            .mapNotNull { pdep: org.gradle.api.artifacts.ProjectDependency ->
                val depPath = pdep.dependencyProject.path
                findProject(depPath)?.tasks?.findByName("jar") as org.gradle.api.tasks.bundling.Jar?
            }
        // 三方 jar：用独立 configuration（不在 compileClasspath 解析时锁定），仅模块信息编译期使用
        val modulePathConfig = configurations.create("compileJava9ModulePath")
        modulePathConfig.isCanBeResolved = true
        modulePathConfig.isCanBeConsumed = false
        modulePathConfig.extendsFrom(compileClasspath)
        // module path = 上游项目 jar + 三方 jar
        val modulePath: org.gradle.api.file.FileCollection = files(upstreamJarTasks.flatMap { it.outputs.files })
            .plus(modulePathConfig)
        val compileModuleInfo9 = tasks.register<JavaCompile>("compileJava9ModuleInfo") {
            source(fileTree(moduleInfo9.parent) { include("module-info.java") })
            destinationDirectory.set(layout.buildDirectory.dir("classes/java9"))
            classpath = files()
            options.release.set(9)
            options.encoding = "UTF-8"
            options.compilerArgs.add("--patch-module")
            options.compilerArgs.add(moduleName + "=" + mainClasses.asPath)
            options.compilerArgs.add("--module-path")
            options.compilerArgs.add(modulePath.asPath)
            dependsOn(tasks.named("classes"))
            upstreamJarTasks.forEach { jarDep: org.gradle.api.tasks.bundling.Jar ->
                dependsOn(jarDep)
            }
        }
        jarTask.configure {
            from(compileModuleInfo9.flatMap { it.destinationDirectory }) {
                into("META-INF/versions/9")
            }
        }
    }
    }

    repositories {
        mavenCentral()
    }

    configure<JavaPluginExtension> {
        withSourcesJar()
        withJavadocJar()
    }

    // 构建期写入版本，运行时可读取（避免源码里硬编码版本号随迭代漂移）
    val generateBuildInfo = tasks.register("generateBuildInfo") {
        val outDir = layout.buildDirectory.dir("generated/build-info")
        val moduleVersion = project.version.toString()
        inputs.property("version", moduleVersion)
        outputs.dir(outDir)
        doLast {
            val dir = outDir.get().asFile
            dir.mkdirs()
            java.io.File(dir, "META-INF/jvfault-build.properties").apply {
                parentFile.mkdirs()
            }.writeText("version=$moduleVersion\n")
        }
    }

    tasks.withType<ProcessResources>().configureEach {
        from(generateBuildInfo)
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        // module-info 描述符需 --release 9（在多版本 JAR 任务里单独设定）；其余模块保持 Java 17 基线
        if (name != "compileJava9ModuleInfo") {
            options.release = 17
        }
        options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all,-processing,-serial"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("failed", "skipped")
            showStackTraces = true
            showExceptions = true
            showCauses = true
        }
        maxParallelForks = 2
    }

    tasks.withType<Javadoc>().configureEach {
        options.encoding = "UTF-8"
        (options as? StandardJavadocDocletOptions)?.apply {
            charSet = "UTF-8"
            docEncoding = "UTF-8"
            addStringOption("Xdoclint:none", "-quiet")
        }
    }

    configure<PublishingExtension> {
        publications {
            create<MavenPublication>("maven") {
                from(components["java"])
                pom {
                    name.set("jvfault-${project.name}")
                    description.set("jvfault Framework module: ${project.name}")
                    url.set("https://github.com/rwxbot/jvfault")
                    licenses {
                        license {
                            name.set("Apache License 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }
                }
            }
        }
    }
}

// distribution 模块自管（BOM 配置在 distribution/build.gradle.kts）
// 这里不需要额外的全局配置，避免 java-library/java-platform 冲突

// ---- 聚合 Javadoc 站点（一次性生成全模块 API 文档，用于 GitHub Pages）----
// 每个模块已各自产出 javadoc jar（withJavadocJar）；此处把它们合并成一个站点。
val javadocAggregate by tasks.registering(Javadoc::class) {
    group = "documentation"
    description = "Aggregate Javadoc for all modules into a single site (build/docs/javadoc-aggregate)."

    val javaProjects = subprojects.filter { it.plugins.hasPlugin("java") }
    val mainSrc = javaProjects.map { p ->
        p.extensions.getByType<org.gradle.api.tasks.SourceSetContainer>().getByName("main")
    }
    // 全部模块的 main 源码合并为单一 FileCollection（含所有 .java）
    source(files(mainSrc.map { it.allJava }))
    // 解析 @link / 继承关系所需的三方依赖 classpath
    classpath = files(mainSrc.map { it.compileClasspath })

    destinationDir = File(buildDir, "docs/javadoc-aggregate")
    isFailOnError = false

    (options as StandardJavadocDocletOptions).apply {
        encoding = "UTF-8"
        charSet = "UTF-8"
        docEncoding = "UTF-8"
        addStringOption("Xdoclint:none", "-quiet")
        windowTitle = "jvfault API"
        docTitle = "jvfault Framework API (v${project.version})"
        header = "jvfault"
        bottom = "jvfault ${project.version} — Licensed under Apache-2.0"
        links("https://docs.oracle.com/en/java/javase/21/docs/api/")
    }
    // 排除模块描述符：避免 javadoc 进入 module 模式，保持传统「包视图」站点
    exclude("**/module-info.java")
}

// ---- JDK 固化（构建可复现）----
// 本机 PATH 上的 java 可能是 12（低于任何模块要求的 17/21），直接 ./gradlew 会以
// 「invalid target release」失败，且报错信息不会提示是 JDK 版本问题 —— 排查成本很高。
// 这里统一指定 toolchain：Gradle 自动定位本机 JDK 21，无需人工 export JAVA_HOME，
// 新人 clone 后 ./gradlew build 即可（CI 的 java-version 同样是 21，两边对齐）。
//
// 注意：toolchain 决定「用哪个 JDK 编译」，options.release 决定「产出什么字节码版本」，
// 二者不冲突 —— 这里固定 21 只为保证编译器可用，各模块仍按自己的 release 产出。
allprojects {
    plugins.withId("java") {
        extensions.configure<JavaPluginExtension> {
            toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
        }
    }
}

