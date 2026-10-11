package com.jvfault.openapi;

import com.jvfault.web.WebApplication;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OpenAPI 3.0 文档生成器 - 从 WebApplication 路由表推导。
 *
 * @since v0.7.0 (2021)
 * @author jvfault team
 */
public class OpenApiGenerator {

    private final WebApplication application;
    private String title = "jvfault API";
    private String version = "1.0.0";
    private String description = "";

    public OpenApiGenerator(WebApplication application) {
        this.application = application;
    }

    public OpenApiGenerator title(String title) {
        this.title = title;
        return this;
    }

    public OpenApiGenerator version(String version) {
        this.version = version;
        return this;
    }

    public OpenApiGenerator description(String description) {
        this.description = description;
        return this;
    }

    /**
     * 生成 OpenAPI 3.0 文档树。
     */
    public Map<String, Object> build() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("openapi", "3.0.3");

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("title", title);
        info.put("description", description);
        info.put("version", version);
        doc.put("info", info);

        Map<String, Object> paths = new LinkedHashMap<>();
        for (com.jvfault.web.routing.RouteEntry route : application.getRouter().getRoutes()) {
            String path = route.getPattern().getPattern()
                    .replaceAll(":([A-Za-z0-9_]+)", "{$1}");
            @SuppressWarnings("unchecked")
            Map<String, Object> pathItem = (Map<String, Object>) paths.computeIfAbsent(path,
                    k -> new LinkedHashMap<>());
            Map<String, Object> operation = new LinkedHashMap<>();
            operation.put("summary", route.getHandler().getDeclaringClass().getSimpleName()
                    + "." + route.getHandler().getName());
            operation.put("responses", defaultResponses());
            // OpenAPI 的 paths 键必须是规范小写（get/post/...），所以固定用 Locale.ROOT：
            // 默认 Locale（如土耳其语）会把大写 I 映射成无点 ı，"OPTIONS" 会写成
            // "optıons"，生成的文档不符合规范，下游工具会认不出这个 method。
            pathItem.put(route.getHttpMethod().toLowerCase(java.util.Locale.ROOT), operation);
        }
        doc.put("paths", paths);
        return doc;
    }

    private Map<String, Object> defaultResponses() {
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("description", "OK");
        Map<String, Object> responses = new LinkedHashMap<>();
        responses.put("200", ok);
        return responses;
    }

    /**
     * 生成 JSON。
     */
    public String toJson() {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(build());
        } catch (Exception e) {
            throw new IllegalStateException("OpenAPI JSON 生成失败", e);
        }
    }

    /**
     * 生成 YAML。
     */
    public String toYaml() {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        return new Yaml(options).dump(build());
    }
}
