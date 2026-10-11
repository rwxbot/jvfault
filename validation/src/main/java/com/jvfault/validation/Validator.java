package com.jvfault.validation;

import java.util.Set;

/**
 * 校验门面 - 快捷入口。
 *
 * <pre>{@code
 * Validator.validateOrThrow(user);       // 失败抛 ValidationException
 * Set<...> violations = Validator.collect(user); // 收集模式
 * }</pre>
 *
 * @since v0.2.0 (2016)
 * @author jvfault team
 */
public final class Validator {

    private static volatile ValidationEngine engine = new DefaultValidationEngine();

    private Validator() {
    }

    public static ValidationEngine getEngine() {
        return engine;
    }

    public static void setEngine(ValidationEngine engine) {
        Validator.engine = engine;
    }

    /**
     * 收集校验违反项。
     */
    public static <T> Set<ConstraintViolation<T>> collect(T object) {
        return engine.validate(object);
    }

    /**
     * 校验失败即抛 ValidationException。
     */
    public static <T> void validateOrThrow(T object) {
        Set<ConstraintViolation<T>> violations = engine.validate(object);
        if (!violations.isEmpty()) {
            throw new ValidationException(violations);
        }
    }
}
