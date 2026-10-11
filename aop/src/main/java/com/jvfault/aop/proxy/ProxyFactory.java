package com.jvfault.aop.proxy;

import com.jvfault.aop.advice.AdviceMethod;
import com.jvfault.aop.aspect.Advisor;
import com.jvfault.aop.interception.MethodInterceptor;
import com.jvfault.aop.interception.MethodInvocation;
import com.jvfault.aop.interception.ReflectiveMethodInvocation;
import com.jvfault.aop.pointcut.PointcutExpression;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 代理工厂 - 为目标 Bean 创建 AOP 代理。
 *
 * <ul>
 *   <li>目标实现接口 → JDK 动态代理（java.lang.reflect.Proxy）</li>
 *   <li>无接口 → ByteBuddy 子类代理（要求非 final 类）</li>
 *   <li>final 类 → 返回原对象（不代理）</li>
 * </ul>
 *
 * <p>执行模型：around 链（按切面 @Order 外到内）→ 终端
 * （before → 目标 → afterReturning/afterThrowing → after）。
 *
 * @since v0.2.0 (2016)
 * @author jvfault team
 */
public class ProxyFactory {

    private final List<Advisor> advisors;
    private final Map<Method, Boolean> matchedCache = new ConcurrentHashMap<>();

    public ProxyFactory(List<Advisor> advisors) {
        this.advisors = advisors;
    }

    /**
     * 创建代理；无切面可匹配或无法代理时返回原对象。
     *
     * <p>具体类优先 ByteBuddy 子类代理（保留具体类型）；
     * 抽象类/纯接口场景用 JDK 动态代理。
     */
    @SuppressWarnings("unchecked")
    public <T> T createProxy(T target) {
        Class<?> targetClass = target.getClass();
        if (advisors.isEmpty() || java.lang.reflect.Modifier.isFinal(targetClass.getModifiers())) {
            return target;
        }
        boolean concrete = !java.lang.reflect.Modifier.isAbstract(targetClass.getModifiers())
                && targetClass != Object.class;
        if (concrete) {
            try {
                return ByteBuddyClassProxy.create(target,
                        (method, args) -> execute(target, method, args));
            } catch (IllegalStateException e) {
                // 类代理失败（如缺构造器）时回退 JDK 接口代理
            }
        }
        if (targetClass.getInterfaces().length > 0) {
            return (T) java.lang.reflect.Proxy.newProxyInstance(
                    targetClass.getClassLoader(),
                    allInterfaces(targetClass),
                    new JdkHandler(target));
        }
        return target;
    }

    private Class<?>[] allInterfaces(Class<?> clazz) {
        List<Class<?>> interfaces = new ArrayList<>();
        Class<?> c = clazz;
        while (c != null) {
            for (Class<?> iface : c.getInterfaces()) {
                if (!interfaces.contains(iface)) {
                    interfaces.add(iface);
                }
            }
            c = c.getSuperclass();
        }
        return interfaces.toArray(new Class<?>[0]);
    }

    /** 统一执行入口（代理回调） */
    Object execute(Object target, Method method, Object[] args) throws Throwable {
        if (!matchedCache.computeIfAbsent(method, m -> isMatched(target.getClass(), m))) {
            try {
                method.setAccessible(true);
                return method.invoke(target, args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause() != null ? e.getCause() : e;
            }
        }

        List<MethodInterceptor> chain = new ArrayList<>();
        // around 链：advisor 已按 order 升序，越靠前越外层
        for (Advisor advisor : advisors) {
            for (AdviceMethod around : advisor.getAdvicesOfKind(AdviceMethod.AdviceKind.AROUND)) {
                if (matches(around, target.getClass(), method)) {
                    chain.add(new AroundAdviceInterceptor(around));
                }
            }
        }
        chain.add(new CoreAdviceInterceptor(advisors, target));
        return new ReflectiveMethodInvocation(target, method, args, chain).proceed();
    }

    private boolean isMatched(Class<?> targetClass, Method method) {
        for (Advisor advisor : advisors) {
            for (AdviceMethod advice : advisor.getAdvices()) {
                if (matches(advice, targetClass, method)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean matches(AdviceMethod advice, Class<?> targetClass, Method method) {
        try {
            return PointcutExpression.parse(advice.getPointcut()).matches(targetClass, method);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("切面 pointcut 表达式非法: " + advice.getPointcut()
                    + " (" + advice.getMethod() + ")", e);
        }
    }

    /** JDK 接口代理 */
    private class JdkHandler implements InvocationHandler {

        private final Object target;

        JdkHandler(Object target) {
            this.target = target;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            // Object 方法直连目标
            if (method.getDeclaringClass() == Object.class) {
                try {
                    return method.invoke(target, args);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    throw e.getCause() != null ? e.getCause() : e;
                }
            }
            // 接口方法 -> 实现类同名方法
            Method impl = target.getClass().getMethod(method.getName(), method.getParameterTypes());
            Object result = execute(target, impl, args);
            return coercePrimitive(method.getReturnType(), result);
        }

        /** 原始类型返回值 null 兜底 */
        private Object coercePrimitive(Class<?> returnType, Object result) {
            if (result != null || !returnType.isPrimitive()) {
                return result;
            }
            if (returnType == boolean.class) return Boolean.FALSE;
            if (returnType == char.class) return '\0';
            if (returnType == byte.class) return (byte) 0;
            if (returnType == short.class) return (short) 0;
            if (returnType == int.class) return 0;
            if (returnType == long.class) return 0L;
            if (returnType == float.class) return 0f;
            return 0d;
        }
    }

    /** @Around 适配器 */
    private static class AroundAdviceInterceptor implements MethodInterceptor {

        private final AdviceMethod advice;

        AroundAdviceInterceptor(AdviceMethod advice) {
            this.advice = advice;
        }

        @Override
        public Object invoke(MethodInvocation invocation) throws Throwable {
            return advice.invoke(invocation);
        }
    }

    /**
     * 终端拦截器：before → 目标 → afterReturning/afterThrowing → after。
     */
    private static class CoreAdviceInterceptor implements MethodInterceptor {

        private final List<Advisor> advisors;
        private final Object target;

        CoreAdviceInterceptor(List<Advisor> advisors, Object target) {
            this.advisors = advisors;
            this.target = target;
        }

        @Override
        public Object invoke(MethodInvocation invocation) throws Throwable {
            Method method = invocation.getMethod();
            Object[] args = invocation.getArguments();

            runAdvice(advisors, AdviceMethod.AdviceKind.BEFORE, target, method, args, null, null);

            Object result;
            try {
                method.setAccessible(true);
                result = method.invoke(target, args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                runAdvice(advisors, AdviceMethod.AdviceKind.AFTER_THROWING, target, method, args, null, cause);
                runAdvice(advisors, AdviceMethod.AdviceKind.AFTER, target, method, args, null, cause);
                throw cause;
            }

            runAdvice(advisors, AdviceMethod.AdviceKind.AFTER_RETURNING, target, method, args, result, null);
            runAdvice(advisors, AdviceMethod.AdviceKind.AFTER, target, method, args, null, null);
            return result;
        }

        private void runAdvice(List<Advisor> advisors, AdviceMethod.AdviceKind kind,
                               Object target, Method method, Object[] args,
                               Object result, Throwable thrown) throws Throwable {
            for (Advisor advisor : advisors) {
                for (AdviceMethod advice : advisor.getAdvicesOfKind(kind)) {
                    if (!pointcutMatches(advice, target.getClass(), method)) {
                        continue;
                    }
                    Object[] adviceArgs = bindAdviceArguments(advice, kind, method, args, result, thrown);
                    try {
                        advice.invoke(adviceArgs);
                    } catch (Throwable t) {
                        if (kind == AdviceMethod.AdviceKind.AFTER_THROWING) {
                            throw t; // 异常通知自身的异常向上传播
                        }
                        // 其他通知异常按语义向调用方传播
                        throw t;
                    }
                }
            }
        }

        private boolean pointcutMatches(AdviceMethod advice, Class<?> targetClass, Method method) {
            return PointcutExpression.parse(advice.getPointcut()).matches(targetClass, method);
        }

        /**
         * 通知方法参数绑定：
         * - MethodInvocation 类型不出现于此（仅 Around 使用）
         * - AFTER_THROWING 第一个 Throwable 参数绑定异常
         * - AFTER_RETURNING 第一个可承接返回值的参数绑定返回值
         * - 其余参数按类型匹配目标方法实参
         */
        private Object[] bindAdviceArguments(AdviceMethod advice, AdviceMethod.AdviceKind kind,
                                             Method targetMethod, Object[] targetArgs,
                                             Object result, Throwable thrown) {
            Class<?>[] pTypes = advice.getMethod().getParameterTypes();
            Object[] bound = new Object[pTypes.length];
            boolean[] used = new boolean[targetArgs == null ? 0 : targetArgs.length];

            for (int i = 0; i < pTypes.length; i++) {
                Class<?> pType = pTypes[i];
                if (kind == AdviceMethod.AdviceKind.AFTER_THROWING
                        && i == 0 && Throwable.class.isAssignableFrom(pType)) {
                    bound[i] = thrown;
                    continue;
                }
                if (kind == AdviceMethod.AdviceKind.AFTER_RETURNING && i == 0
                        && (result == null || pType.isInstance(result))) {
                    bound[i] = result;
                    continue;
                }
                // 按类型匹配目标方法实参（首个未占用者）
                Object matched = null;
                boolean found = false;
                if (targetArgs != null) {
                    for (int a = 0; a < targetArgs.length; a++) {
                        if (!used[a] && targetArgs[a] != null && pType.isInstance(targetArgs[a])) {
                            matched = targetArgs[a];
                            used[a] = true;
                            found = true;
                            break;
                        }
                    }
                }
                bound[i] = found ? matched : defaultValue(pType);
            }
            return bound;
        }

        private Object defaultValue(Class<?> type) {
            if (!type.isPrimitive()) {
                return null;
            }
            if (type == boolean.class) return Boolean.FALSE;
            if (type == char.class) return '\0';
            if (type == byte.class) return (byte) 0;
            if (type == short.class) return (short) 0;
            if (type == int.class) return 0;
            if (type == long.class) return 0L;
            if (type == float.class) return 0f;
            return 0d;
        }
    }
}
