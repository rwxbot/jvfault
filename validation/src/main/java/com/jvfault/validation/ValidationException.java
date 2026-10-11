package com.jvfault.validation;

import java.util.Set;

/**
 * 校验失败异常 - 携带全部违反项。
 *
 * @since v0.2.0 (2016)
 * @author jvfault team
 */
public class ValidationException extends RuntimeException {

    private final Set<ConstraintViolation<?>> violations;

    /**
     * @param violations 任意 {@code ConstraintViolation} 子类型的集合。
     *     形参写成 {@code Set<? extends ConstraintViolation<?>>} 而不是
     *     {@code Set<ConstraintViolation<?>>}，是为了让调用方
     *     （如 {@code Validator.validateOrThrow} 手上的
     *     {@code Set<ConstraintViolation<T>>}）能直接传入 —— 否则每个调用点
     *     都要写 {@code (Set<ConstraintViolation<?>>) (Set<?>) violations}
     *     这种双重强转并各自吞一条 {@code [unchecked]}。
     *     对既有调用方是纯放宽，源码兼容。
     */
    public ValidationException(Set<? extends ConstraintViolation<?>> violations) {
        super("Validation failed: " + violations.size() + " violation(s) - " + violations);
        @SuppressWarnings("unchecked")
        // 安全：ConstraintViolation 的类型参数只做编译期路径追踪，运行期已擦除，
        // 且本类只以 ConstraintViolation<?> 的形式读取，不会写回具体类型。
        Set<ConstraintViolation<?>> erased = (Set<ConstraintViolation<?>>) violations;
        this.violations = erased;
    }

    public Set<ConstraintViolation<?>> getViolations() {
        return violations;
    }
}
