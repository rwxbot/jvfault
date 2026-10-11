package com.jvfault.config;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JSON 属性源（嵌套结构自动扁平化）。
 *
 * @since v0.3.0 (2017)
 * @author jvfault team
 */
public class JsonPropertySource extends PropertySource {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, Object> flat;

    public JsonPropertySource(String name, InputStream in) {
        super(name);
        Map<String, Object> root;
        try {
            // readValue(in, Map.class) 是裸类型 → 编译期 [unchecked]。
            // 用 TypeReference 带上泛型，与字段声明 Map<String,Object> 对齐。
            root = MAPPER.readValue(in,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
        } catch (Exception e) {
            throw new ConfigException("JSON 配置解析失败: " + name, null, e);
        }
        this.flat = PropertyFlattener.flatten(root);
    }

    public JsonPropertySource(String name, Map<String, Object> flat) {
        super(name);
        this.flat = flat;
    }

    public Map<String, Object> getFlat() {
        return flat;
    }

    @Override
    public String getProperty(String key) {
        Object value = flat.get(key);
        return value != null ? String.valueOf(value) : null;
    }
}
