package com.jvfault.openapi;

import com.jvfault.web.WebApplication;
import com.jvfault.web.routing.RouteEntry;
import com.jvfault.web.routing.RoutePattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OpenAPI 文档的「Locale 无关性」回归测试。
 *
 * <p>背景：{@code String.toLowerCase()} / {@code toUpperCase()} 用<b>默认 Locale</b>
 * 做语言相关的大小写映射。土耳其语（tr_TR）下大写 {@code I} 会被映射成<b>无点</b>
 * 的 {@code ı}（U+0131），于是 {@code "OPTIONS".toLowerCase()} 得到
 * {@code "optıons"} 而不是 {@code "options"}。OpenAPI 3.0 的 {@code paths} 对象
 * 里，HTTP 方法键必须是规范小写，写错下游工具就认不出这个 method。
 *
 * <p>本测试刻意把默认 Locale 临时切到 tr_TR，确保它<b>真的走在会触发缺陷的路径上</b>：
 * 前置断言先验证 {@code "OPTIONS".toLowerCase()} 此刻确实等于 {@code "optıons"}，
 * 否则说明环境没切成功，测试会通过得毫无意义（空洞通过）。
 *
 * @since v1.0.16 (2026)
 */
@DisplayName("OpenAPI 生成的 Locale 无关性")
class OpenApiLocaleIndependenceTest {

    @Test
    @DisplayName("土耳其语默认 Locale 下 HTTP 方法键仍是规范小写 options（不是 optıons）")
    void httpMethodKeyIsLocaleIndependent() throws Exception {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));

            // 前置断言：证明当前确实处于会触发缺陷的 Locale 环境。
            // 若这条不成立，下面的断言通过也说明不了任何问题。
            assertEquals("optıons", "OPTIONS".toLowerCase(),
                    "前置断言失败：默认 Locale 未生效为 tr_TR，本测试会空洞通过");

            WebApplication app = new WebApplication(null);
            Method handler = Handler.class.getDeclaredMethod("preflight");
            app.getRouter().add(new RouteEntry("OPTIONS", new RoutePattern("/pets"),
                    new Handler(), handler));

            Map<String, Object> doc = new OpenApiGenerator(app).build();

            @SuppressWarnings("unchecked")
            Map<String, Object> paths = (Map<String, Object>) doc.get("paths");
            assertNotNull(paths, "文档里应有 paths");
            @SuppressWarnings("unchecked")
            Map<String, Object> pets = (Map<String, Object>) paths.get("/pets");
            assertNotNull(pets, "paths 里应有 /pets，实际=" + paths.keySet());

            assertTrue(pets.containsKey("options"),
                    "OpenAPI 的 paths 键必须是规范小写 options（Locale.ROOT 转换），"
                            + "实际键集合=" + pets.keySet());
        } finally {
            // 必须还原：Locale.setDefault 是 JVM 全局状态，漏还原会污染同 JVM 的其它测试
            Locale.setDefault(original);
        }
    }

    /** 供 RouteEntry 反射使用的处理器壳子 */
    static class Handler {
        public String preflight() {
            return "";
        }
    }
}
