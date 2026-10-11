package com.jvfault.tests;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * README 文档宣称一致性回归 —— 防止「数字漂移」类缺陷。
 *
 * <p><b>背景（2026-09-24 复盘）</b>：
 * 「JDK 分层漂移」只是<b>文档宣称漂移</b>这一类系统性问题的第一个实例。同一项目里已经发生过：
 * <ul>
 *   <li>集成测试数 28 → 18 的静默回退（无人发现 README 仍写旧值）</li>
 *   <li>静态假 badge（CI 实际状态与 badge 不符）</li>
 *   <li>测试总数 269 → 274 后 README 未同步（badge、维度表、快速开始三处都过期）</li>
 *   <li>「模块含测试」口径写成 38，实际 39 个模块全部含测试</li>
 * </ul>
 * 这些数字分散在 README 多处，改代码后极易漏改文档。本测试把它们<b>一次性</b>固化为机器校验：
 * 每一项都同时核对「源码事实」与「README 陈述」，二者任一漂移即失败，逼着改动者同步两边。
 *
 * <p><b>设计原则（与 BuildBaselineConsistencyTest 一致，故意设摩擦）</b>：
 * <ol>
 *   <li>每个数字在源码里<b>真实派生</b>（解析 settings.gradle.kts / 扫描目录树），不是拍脑袋常量；</li>
 *   <li>派生值再与 README 里写死的数字比对，不一致就红；</li>
 *   <li>新增模块 / 改测试数 / 加传输适配时，<b>必须</b>同步更新本文件与 README，否则 CI 立刻报警。</li>
 * </ol>
 *
 * @since v1.0.9 (2026)
 */
@DisplayName("README 文档宣称一致性（防止数字漂移）")
class ReadmeFactsConsistencyTest {

    // ── 单一事实来源：源码派生值必须与这些常量一致；常量漂移由「源码派生」侧捕获 ──
    private static final int EXPECTED_FRAMEWORK_MODULES = 40;
    /**
     * 版本示例数：v0.8.0 → v1.0.0 共 5 个，v1.0.14 新增 {@code spring-boot-bridge}
     * （Spring Boot 3 ↔ jvfault 双向桥接可运行示例）→ 6 个。
     */
    private static final int EXPECTED_EXAMPLES = 6;
    private static final int EXPECTED_TRANSPORT = 7;
    private static final int EXPECTED_MODULES_WITH_TESTS = 39;
    private static final int EXPECTED_MR_JAR = 38;
    /**
     * 测试总数（badge + 维度表共用）。
     *
     * <p><b>⚠️ 本常量无法自我校验</b>：测试总数由 Gradle 产出，本测试只能比对 README 与本常量。
     * <b>新增/删除任何测试后必须</b>：跑全量 {@code ./gradlew test}，核对真实总数，再更新本常量与 README。
     * （v1.0.9 曾漏掉这步 —— 加了 7 个测试却仍写 274，导致 README 静默漂移；v1.0.10 修正为 281。）
     */
    private static final int EXPECTED_TEST_COUNT = 310;

    private static final Pattern RELEASE_DECL =
            Pattern.compile("options\\.release\\s*=\\s*(\\d+)");

    private static Path projectRoot() {
        String override = System.getProperty("jvfault.project.root");
        String base = (override != null && !override.isEmpty())
                ? override : System.getProperty("user.dir");
        return Paths.get(base).toAbsolutePath().normalize();
    }

    // ─────────────────────────────── 源码事实派生 ───────────────────────────────

    /** 解析 settings.gradle.kts 的两段 listOf(...).forEach，分别得到框架模块与示例模块。 */
    private static List<List<String>> parseSettingsModules() throws IOException {
        Path settings = projectRoot().resolve("settings.gradle.kts");
        String text = new String(Files.readAllBytes(settings), StandardCharsets.UTF_8);
        Matcher block = Pattern.compile("listOf\\((.*?)\\)\\.forEach", Pattern.DOTALL)
                .matcher(text);
        List<List<String>> result = new ArrayList<>();
        while (block.find()) {
            List<String> names = new ArrayList<>();
            Matcher q = Pattern.compile("\"([^\"]+)\"").matcher(block.group(1));
            while (q.find()) names.add(q.group(1));
            result.add(names);
        }
        return result;
    }

    private int countFrameworkModules() throws IOException {
        return parseSettingsModules().get(0).size();
    }

    private int countExamples() throws IOException {
        return parseSettingsModules().get(1).size();
    }

    private int countTransport() throws IOException {
        int n = 0;
        for (String m : parseSettingsModules().get(0)) {
            if (m.startsWith("transport-")) n++;
        }
        return n;
    }

    private int countModulesWithTests() throws IOException {
        int n = 0;
        for (String m : parseSettingsModules().get(0)) {
            Path testRoot = projectRoot().resolve(m).resolve("src/test/java");
            if (!Files.isDirectory(testRoot)) continue;
            // 必须是「有测试文件」，不能只看目录是否存在：
            // `test` 模块有一个空的 src/test/java 目录（0 个 .java），
            // 只判目录会把「全部 40 个均含测试」撑成假话。
            try (java.util.stream.Stream<Path> files = Files.walk(testRoot)) {
                if (files.anyMatch(p -> p.toString().endsWith(".java"))) n++;
            }
        }
        return n;
    }

    private int countMrJar() throws IOException {
        int n = 0;
        for (String m : parseSettingsModules().get(0)) {
            if (Files.isRegularFile(
                    projectRoot().resolve(m).resolve("src/main/java9/module-info.java"))) {
                n++;
            }
        }
        return n;
    }

    /** 扫描各模块 build.gradle.kts 的 options.release，返回 模块名→release。 */
    private Map<String, Integer> scanActualRelease() throws IOException {
        Map<String, Integer> actual = new LinkedHashMap<>();
        Path root = projectRoot();
        try (var top = Files.newDirectoryStream(root)) {
            for (Path sub : top) {
                if (!Files.isDirectory(sub) || sub.getFileName().toString().startsWith(".")) {
                    continue;
                }
                String name = sub.getFileName().toString();
                Path bf = sub.resolve("build.gradle.kts");
                if (Files.isRegularFile(bf)) {
                    Integer r = extractRelease(bf);
                    if (r != null) actual.put(name, r);
                }
                if (name.equals("examples")) {
                    try (var exs = Files.newDirectoryStream(sub)) {
                        for (Path ex : exs) {
                            if (!Files.isDirectory(ex)) continue;
                            Path ebf = ex.resolve("build.gradle.kts");
                            if (Files.isRegularFile(ebf)) {
                                Integer r = extractRelease(ebf);
                                if (r != null) {
                                    actual.put("examples/" + ex.getFileName(), r);
                                }
                            }
                        }
                    }
                }
            }
        }
        return actual;
    }

    private Integer extractRelease(Path buildFile) throws IOException {
        String text = new String(Files.readAllBytes(buildFile), StandardCharsets.UTF_8);
        Matcher m = RELEASE_DECL.matcher(text);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    // ─────────────────────────────── README 解析 ───────────────────────────────

    private List<String> readmeLines() throws IOException {
        Path readme = projectRoot().resolve("README.md");
        assertTrue(Files.isRegularFile(readme), "README.md 不存在：" + readme);
        return Files.readAllLines(readme, StandardCharsets.UTF_8);
    }

    /** 在「维度 / 现状」表里，按维度名取单元格文本。 */
    private String dimensionCell(List<String> lines, String dimName) {
        boolean inTable = false;
        for (String line : lines) {
            if (line.trim().startsWith("| 维度 | 现状 |")) {
                inTable = true;
                continue;
            }
            if (inTable) {
                if (!line.trim().startsWith("|")) break;   // 表结束
                String[] cells = line.split("\\|");
                if (cells.length >= 3) {
                    String head = cells[1].trim();
                    if (head.equals(dimName)) return cells[2].trim();
                }
            }
        }
        return null;
    }

    /** 从任意文本里取第一个整数。 */
    private static Integer firstInt(String s) {
        if (s == null) return null;
        Matcher m = Pattern.compile("(\\d+)").matcher(s);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    // ─────────────────────────────── 测试用例 ───────────────────────────────

    @Test
    @DisplayName("模块数：源码派生 39 == 常量 == README「39 个」")
    void frameworkModuleCountConsistent() throws IOException {
        int derived = countFrameworkModules();
        assertEquals(EXPECTED_FRAMEWORK_MODULES, derived,
                "settings.gradle.kts 里框架模块数变了？同步本常量与 README");
        Integer readme = firstInt(dimensionCell(readmeLines(), "模块"));
        assertEquals(EXPECTED_FRAMEWORK_MODULES, readme,
                "README「维度」表里的模块数与源码不符（应为 39）");
    }

    @Test
    @DisplayName("版本示例数：源码派生 5 == 常量 == README「5 个」")
    void exampleCountConsistent() throws IOException {
        int derived = countExamples();
        assertEquals(EXPECTED_EXAMPLES, derived,
                "settings.gradle.kts 里示例数变了？同步本常量与 README");
        Integer readme = firstInt(dimensionCell(readmeLines(), "版本示例"));
        assertEquals(EXPECTED_EXAMPLES, readme,
                "README「维度」表里的版本示例数与源码不符（应为 12）");
    }

    @Test
    @DisplayName("传输适配数：源码派生 7 == 常量 == README「7 个」")
    void transportCountConsistent() throws IOException {
        int derived = countTransport();
        assertEquals(EXPECTED_TRANSPORT, derived,
                "transport-* 模块数变了？同步本常量与 README");
        List<String> lines = readmeLines();
        // 同样用常量拼，不写死 "7"
        String want = EXPECTED_TRANSPORT + " 个均含真实集成测试";
        boolean mentions = lines.stream().anyMatch(l -> l.contains(want));
        assertTrue(mentions, "README 未以「" + want + "」声明传输适配数（源码派生=" + derived + "）");
    }

    @Test
    @DisplayName("含测试模块数：源码派生 39 == 常量 == README「39 个含测试」")
    void modulesWithTestsConsistent() throws IOException {
        int derived = countModulesWithTests();
        assertEquals(EXPECTED_MODULES_WITH_TESTS, derived,
                "含 src/test/java 的模块数变了？同步本常量与 README");
        String cell = dimensionCell(readmeLines(), "模块");
        assertNotNull(cell, "README「维度」表缺少「模块」行");
        // 用常量而不是写死 "39" —— 否则加模块后这里会红（曾漏改过一次）
        assertTrue(cell.contains(String.valueOf(EXPECTED_MODULES_WITH_TESTS)),
                "README 模块行未写 " + EXPECTED_MODULES_WITH_TESTS + "：" + cell);
        assertTrue(cell.contains("含测试"),
                "README 模块行应写明含测试的模块数，当前：" + cell);
    }

    @Test
    @DisplayName("MR-JAR 数：源码派生 38 == 常量 == README「38 个模块」")
    void mrJarCountConsistent() throws IOException {
        int derived = countMrJar();
        assertEquals(EXPECTED_MR_JAR, derived,
                "提供 META-INF/versions/9/module-info.java 的模块数变了？同步本常量与 README");
        Integer readme = firstInt(dimensionCell(readmeLines(), "JPMS"));
        assertEquals(EXPECTED_MR_JAR, readme,
                "README「维度」表 JPMS 行的 MR-JAR 模块数与源码不符（应为 38）");
    }

    @Test
    @DisplayName("测试总数：badge 与维度表都必须是 EXPECTED_TEST_COUNT（= 实际测试总数）")
    void testCountConsistent() throws IOException {
        List<String> lines = readmeLines();
        String whole = String.join("\n", lines);
        // 1) Tests badge：tests-274 passing（badge URL 里空格是 %20 编码）
        Matcher badge = Pattern.compile("tests-(\\d+)(?:%20|\\s+)passing").matcher(whole);
        assertTrue(badge.find(), "README Tests badge 未写 tests-NNN passing 形态");
        assertEquals(EXPECTED_TEST_COUNT, Integer.valueOf(badge.group(1)),
                "README Tests badge 的测试数与实际不符（应等于 EXPECTED_TEST_COUNT；改测试后请同步本常量与 README）");
        // 2) 维度表「测试」行也要等于 EXPECTED_TEST_COUNT
        Integer readme = firstInt(dimensionCell(lines, "测试"));
        assertEquals(EXPECTED_TEST_COUNT, readme,
                "README「维度」表「测试」行的数字与 badge 不符（应等于 EXPECTED_TEST_COUNT）");
    }

    /**
     * 版本 badge ↔ {@code gradle.properties}。
     *
     * <p>两边都从源码派生，<b>不需要维护常量</b> —— 所以这条永远不会因为「忘了改常量」而假红。
     * 加它是因为 v1.0.11 时发现 Version badge 长期停在 {@code v1.0.8}，
     * 而实际版本已经是 1.0.11（v1.0.9 / v1.0.10 连续两次漏改）。
     */
    @Test
    @DisplayName("版本：README Version badge == gradle.properties 的 jvfaultVersion")
    void versionBadgeMatchesGradleProperties() throws IOException {
        Path props = projectRoot().resolve("gradle.properties");
        assertTrue(Files.isRegularFile(props), "gradle.properties 不存在：" + props);
        String propsText = new String(Files.readAllBytes(props), StandardCharsets.UTF_8);
        Matcher ver = Pattern.compile("jvfaultVersion\\s*=\\s*(\\S+)").matcher(propsText);
        assertTrue(ver.find(), "gradle.properties 里找不到 jvfaultVersion");
        String actual = ver.group(1).trim();

        String readme = String.join("\n", readmeLines());
        Matcher badge = Pattern.compile("release-v(\\d+\\.\\d+\\.\\d+)-blue").matcher(readme);
        assertTrue(badge.find(), "README 里找不到 release-vX.Y.Z 形式的 Version badge");
        String declared = badge.group(1).trim();

        assertEquals(actual, declared,
                "README Version badge 与 gradle.properties 不一致（badge=v" + declared
                        + "，实际=" + actual + "）\n修复：把 README 的 Version badge 改成 v" + actual);
    }

    /**
     * README 里「含 N 例 JPMS 多版本 JAR 回归」的 N ↔ {@code JpmsModulePathSmokeTest} 里
     * {@code @Test} 的实际个数。两边都从源码派生，无需维护常量。
     */
    @Test
    @DisplayName("JPMS 回归例数：README「含 N 例」== JpmsModulePathSmokeTest 的 @Test 实际个数")
    void jpmsRegressionCountMatches() throws IOException {
        Path src = projectRoot()
                .resolve("tests/src/test/java/com/jvfault/tests/JpmsModulePathSmokeTest.java");
        assertTrue(Files.isRegularFile(src), "找不到 JPMS 回归测试源码：" + src);
        String text = new String(Files.readAllBytes(src), StandardCharsets.UTF_8);
        Matcher t = Pattern.compile("@Test\\b").matcher(text);
        int actual = 0;
        while (t.find()) {
            actual++;
        }
        assertTrue(actual > 0, "JpmsModulePathSmokeTest 里没数到 @Test");

        String readme = String.join("\n", readmeLines());
        Matcher claim = Pattern.compile("含\\s*(\\d+)\\s*例\\s*JPMS").matcher(readme);
        assertTrue(claim.find(), "README 里找不到「含 N 例 JPMS」的宣称");
        assertEquals(actual, Integer.valueOf(claim.group(1)),
                "README 的 JPMS 回归例数与实际不符（实际 " + actual + " 个 @Test）");
    }

    /**
     * README 里「N 个 broker」↔ {@code ci.yml} 里真实 service 的个数。
     * 两边都从源码派生，无需常量 —— CI 加减 broker 却没改文档时这里会红。
     */
    @Test
    @DisplayName("CI：README「N 个 broker」== ci.yml 里 service 的实际个数")
    void ciBrokerCountMatchesReadme() throws IOException {
        List<String> ci = ciWorkflowLines();
        int actual = 0;
        for (String line : ci) {
            if (line.trim().startsWith("image:")) {
                actual++;
            }
        }
        assertTrue(actual > 0, "ci.yml 里没数到任何 service image");

        String readme = String.join("\n", readmeLines());
        Matcher claim = Pattern.compile("(\\d+)\\s*个\\s*(?:真实\\s*)?broker").matcher(readme);
        assertTrue(claim.find(), "README 里找不到「N 个 broker」的宣称");
        assertEquals(actual, Integer.valueOf(claim.group(1)),
                "README 的 broker 数与 ci.yml 的 service 数不符（ci.yml 实际 " + actual + " 个）");
    }

    /**
     * CI 的 JDK 必须<b>覆盖</b>所有模块里最高的 {@code options.release}。
     *
     * <p>这是「构建用 JDK 21、产物仍 --release 8」这条承诺能成立的前提：
     * 若某天新增了 release 25 的模块而 CI 还是 21，构建会直接失败。
     * 同样两边派生、无需常量。
     */
    @Test
    @DisplayName("CI：JDK 版本必须覆盖所有模块中最高的 options.release")
    void ciJdkCoversHighestRelease() throws IOException {
        int ciJava = -1;
        for (String line : ciWorkflowLines()) {
            Matcher m = Pattern.compile("java-version:\\s*['\"]?(\\d+)").matcher(line);
            if (m.find()) {
                ciJava = Integer.parseInt(m.group(1));
                break;
            }
        }
        assertTrue(ciJava > 0, "ci.yml 里没找到 java-version");

        int maxRelease = 0;
        for (Integer r : scanActualRelease().values()) {
            maxRelease = Math.max(maxRelease, r);
        }
        assertTrue(maxRelease > 0, "没扫描到任何 options.release");

        assertTrue(ciJava >= maxRelease,
                "CI 的 JDK " + ciJava + " 低于模块里最高的 options.release " + maxRelease
                        + " —— 构建会失败，请升级 ci.yml 的 java-version");
    }

    /** 读取 CI 工作流（不存在就直接失败，避免「悄悄跳过检查」）。 */
    private List<String> ciWorkflowLines() throws IOException {
        Path ci = projectRoot().resolve(".github/workflows/ci.yml");
        assertTrue(Files.isRegularFile(ci), "找不到 CI 工作流：" + ci);
        return Files.readAllLines(ci, StandardCharsets.UTF_8);
    }
}
