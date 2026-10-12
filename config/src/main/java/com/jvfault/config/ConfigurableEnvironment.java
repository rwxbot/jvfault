package com.jvfault.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 可配置环境 - 有序属性源集合 + Profile 管理。
 *
 * <p>属性解析顺序 = 注册顺序（先注册者优先，程序自己的源应插到最前）。
 *
 * @since v0.3.0 (2017)
 * @author jvfault team
 */
public class ConfigurableEnvironment {

    private final List<PropertySource> propertySources = new ArrayList<>();
    private final List<String> activeProfiles = new ArrayList<>();

    /**
     * 用初始属性源 + profile 直接装配。
     *
     * <p>构造器不可覆写：子类若在构造器里调用可覆写的 add/set 方法，this 会
     * 逃逸（JDK 21 [this-escape]）。所有装配动作必须在构造器外完成后经此传入。
     */
    /**
     * 空环境（属性源与 profile 后续再添加）。
     * 显式保留：一旦写了带参构造器，编译器就不再自动生成无参构造器。
     */
    public ConfigurableEnvironment() {
    }

    protected ConfigurableEnvironment(List<PropertySource> sources, String[] profiles) {
        if (sources != null) {
            propertySources.addAll(sources);
        }
        if (profiles != null) {
            activeProfiles.addAll(Arrays.asList(profiles));
        }
    }

    // ============ 属性解析 ============

    public String getProperty(String key) {
        for (PropertySource source : propertySources) {
            String value = source.getProperty(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    public String getProperty(String key, String defaultValue) {
        String value = getProperty(key);
        return value != null ? value : defaultValue;
    }

    public String getRequiredProperty(String key) {
        String value = getProperty(key);
        if (value == null) {
            throw new ConfigException("必需的配置项缺失", key);
        }
        return value;
    }

    public boolean containsProperty(String key) {
        return getProperty(key) != null;
    }

    /** 任意类型读取（含类型转换） */
    public <T> T get(String key, Class<T> targetType) {
        String raw = getProperty(key);
        if (raw == null) {
            return null;
        }
        return targetType.cast(convert(raw, targetType, key));
    }

    public int getInt(String key, int defaultValue) {
        String raw = getProperty(key);
        return raw != null ? ((Number) convert(raw, Integer.class, key)).intValue() : defaultValue;
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String raw = getProperty(key);
        return raw != null ? (Boolean) convert(raw, Boolean.class, key) : defaultValue;
    }

    /** 绑定用类型转换 */
    Object convert(String raw, Class<?> targetType, String key) {
        try {
            if (targetType == String.class) return raw;
            if (targetType == int.class || targetType == Integer.class) return Integer.valueOf(raw.trim());
            if (targetType == long.class || targetType == Long.class) return Long.valueOf(raw.trim());
            if (targetType == double.class || targetType == Double.class) return Double.valueOf(raw.trim());
            if (targetType == float.class || targetType == Float.class) return Float.valueOf(raw.trim());
            if (targetType == short.class || targetType == Short.class) return Short.valueOf(raw.trim());
            if (targetType == boolean.class || targetType == Boolean.class) {
                return Boolean.valueOf(raw.trim()) || "1".equals(raw.trim());
            }
            if (targetType.isEnum()) {
                @SuppressWarnings({"unchecked", "rawtypes"})
                Object e = Enum.valueOf((Class<? extends Enum>) targetType, raw.trim());
                return e;
            }
            throw new ConfigException("不支持的配置类型: " + targetType.getName(), key);
        } catch (ConfigException e) {
            throw e;
        } catch (Exception e) {
            throw new ConfigException("配置类型转换失败: " + raw + " -> " + targetType.getSimpleName(), key, e);
        }
    }

    // ============ 占位符 ============

    /**
     * 解析 ${key:default} 占位符（支持嵌套一层）。
     * 未提供默认值且无法解析时保留原文。
     */
    public String resolvePlaceholders(String text) {
        if (text == null || !text.contains("${")) {
            return text;
        }
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            int start = text.indexOf("${", i);
            if (start < 0) {
                out.append(text, i, text.length());
                break;
            }
            int end = text.indexOf('}', start);
            if (end < 0) {
                out.append(text, i, text.length());
                break;
            }
            out.append(text, i, start);
            String expr = text.substring(start + 2, end);
            String key = expr;
            String defaultValue = null;
            int colon = expr.indexOf(':');
            if (colon >= 0) {
                key = expr.substring(0, colon);
                defaultValue = expr.substring(colon + 1);
            }
            String resolved = getProperty(key.trim());
            if (resolved != null) {
                out.append(resolved);
            } else if (defaultValue != null) {
                out.append(defaultValue);
            } else {
                out.append(text, start, end + 1); // 保留原文
            }
            i = end + 1;
        }
        return out.toString();
    }

    // ============ 属性源管理 ============

    /**
     * 添加属性源（追加到最低优先级）。
     */
    public ConfigurableEnvironment addPropertySource(PropertySource source) {
        propertySources.add(source);
        return this;
    }

    /**
     * 添加属性源到最高优先级（队首）。
     */
    public ConfigurableEnvironment addFirst(PropertySource source) {
        propertySources.add(0, source);
        return this;
    }

    public List<PropertySource> getPropertySources() {
        return new ArrayList<>(propertySources);
    }

    /**
     * 合并另一个环境的全部属性源（追加到最低优先级）。
     */
    public ConfigurableEnvironment merge(ConfigurableEnvironment other) {
        propertySources.addAll(other.propertySources);
        return this;
    }

    // ============ Profile ============

    public String[] getActiveProfiles() {
        return activeProfiles.toArray(new String[0]);
    }

    public void setActiveProfiles(String... profiles) {
        activeProfiles.clear();
        activeProfiles.addAll(Arrays.asList(profiles));
    }

    public void addActiveProfile(String profile) {
        activeProfiles.add(profile);
    }

    public boolean acceptsProfiles(String... profiles) {
        for (String p : profiles) {
            if (activeProfiles.contains(p)) {
                return true;
            }
        }
        return false;
    }
}
