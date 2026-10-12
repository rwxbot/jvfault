package com.jvfault.config;

import com.jvfault.core.annotation.Component;
import com.jvfault.core.annotation.Inject;

/**
 * 默认 Environment Bean。
 *
 * <p>加载顺序：classpath application.{yml,properties,json} →
 * application-{profile}.* → 系统属性 → 环境变量。
 * Profile 通过系统属性 {@code jvfault.profiles}（逗号分隔）指定。
 *
 * @since v0.3.0 (2017)
 * @author jvfault team
 */
@Component
public class DefaultConfigEnvironment extends ConfigurableEnvironment {

    public static final String PROFILES_PROPERTY = "jvfault.profiles";

    @Inject
    public DefaultConfigEnvironment() {
        // 装配在另一个实例上完成后经 super(...) 传入，避免构造期 this 逃逸
        this(loadInitial());
    }

    private DefaultConfigEnvironment(ConfigurableEnvironment initial) {
        super(initial.getPropertySources(), initial.getActiveProfiles());
    }

    private static ConfigurableEnvironment loadInitial() {
        String raw = System.getProperty(PROFILES_PROPERTY, "");
        ConfigurableEnvironment holder = new ConfigurableEnvironment();
        if (!raw.trim().isEmpty()) {
            String[] profiles = raw.trim().split("\\s*,\\s*");
            holder.setActiveProfiles(profiles);
            copyInto(holder, ConfigLoader.load(profiles));
        } else {
            copyInto(holder, ConfigLoader.load());
        }
        return holder;
    }

    /** 复制 loader 装配好的属性源（在传入的目标实例上累加，不动 this） */
    private static void copyInto(ConfigurableEnvironment target, ConfigurableEnvironment env) {
        for (PropertySource source : env.getPropertySources()) {
            target.addPropertySource(source);
        }
        for (String p : env.getActiveProfiles()) {
            target.addActiveProfile(p);
        }
    }
}
