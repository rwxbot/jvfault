package com.jvfault.core.container;

import com.jvfault.core.annotation.Bean;
import com.jvfault.core.annotation.Component;
import jakarta.inject.Named;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.*;

/**
 * Bean 定义元数据 - 描述如何创建和配置 Bean
 * 对应 Spring: BeanDefinition / RootBeanDefinition
 * 
 * @since v0.1.0 (2015)
 * @author jvfault team
 */
public class BeanDefinition {

    // ============ 基础标识 ============
    private final String name;
    private final Class<?> beanClass;
    private final Class<?> factoryClass;  // @Configuration 类
    private final Method factoryMethod;   // @Bean 方法
    private final Constructor<?> constructor; // 选定的构造器

    // ============ 作用域与生命周期 ============
    private Component.Scope scope = Component.Scope.SINGLETON;
    private boolean lazyInit = false;
    private boolean primary = false;
    private boolean autowireCandidate = true;
    private String initMethodName;
    private String destroyMethodName;

    // ============ 依赖关系 ============
    private final List<DependencyDescriptor> constructorDependencies = new ArrayList<>();
    private final List<DependencyDescriptor> fieldDependencies = new ArrayList<>();
    private final List<DependencyDescriptor> methodDependencies = new ArrayList<>();

    // ============ 限定符 ============
    private final Set<String> qualifiers = new LinkedHashSet<>();

    // ============ 注解元数据 ============
    private final Map<Class<? extends Annotation>, Annotation> annotations = new LinkedHashMap<>();

    // ============ 运行时状态 ============
    private volatile Object instance; // 单例实例缓存
    private volatile boolean initialized = false;
    private volatile FactoryBean<?> factoryBean; // FactoryBean 延迟创建

    // ============ 构造器 ============

    public BeanDefinition(String name, Class<?> beanClass) {
        this.name = name;
        this.beanClass = beanClass;
        this.factoryClass = null;
        this.factoryMethod = null;
        this.constructor = resolveConstructor(beanClass);
    }

    public BeanDefinition(String name, Class<?> factoryClass, Method factoryMethod) {
        this.name = name;
        this.beanClass = factoryMethod.getReturnType();
        this.factoryClass = factoryClass;
        this.factoryMethod = factoryMethod;
        this.constructor = null;
    }

    // ============ 静态工厂方法 ============

    public static BeanDefinition fromComponent(Class<?> clazz) {
        Component comp = clazz.getAnnotation(Component.class);
        String name = (comp != null && !comp.value().isEmpty()) ? comp.value() : defaultBeanName(clazz);
        BeanDefinition bd = new BeanDefinition(name, clazz);
        if (comp != null) {
            bd.scope = comp.scope();
            bd.primary = comp.primary();
        }
        // 记录类上的注解元数据（供 getBeansWithAnnotation 等内省 API 使用）
        for (Annotation annotation : clazz.getAnnotations()) {
            bd.addAnnotation(annotation);
        }
        // 记录 @Named 限定符
        Named named = clazz.getAnnotation(Named.class);
        if (named != null && !named.value().isEmpty()) {
            bd.addQualifier(named.value());
        }
        return bd;
    }

    public static BeanDefinition fromBeanMethod(Class<?> configClass, Method method) {
        Bean bean = method.getAnnotation(Bean.class);
        String name = (bean != null && !bean.value().isEmpty()) ? bean.value() : method.getName();
        BeanDefinition bd = new BeanDefinition(name, configClass, method);
        if (bean != null) {
            bd.scope = parseScope(bean.scope());
            bd.primary = bean.primary();
            bd.initMethodName = bean.initMethod();
            bd.destroyMethodName = bean.destroyMethod();
        }
        return bd;
    }

    private static String defaultBeanName(Class<?> clazz) {
        String simple = clazz.getSimpleName();
        return Character.toLowerCase(simple.charAt(0)) + simple.substring(1);
    }

    private static Component.Scope parseScope(String scope) {
        if (scope == null || scope.isEmpty()) return Component.Scope.SINGLETON;
        try {
            // 显式 Locale.ROOT：默认 Locale 下 toUpperCase() 做语言相关映射，
            // 土耳其语把 i → İ（U+0130），实测 "singleton".toUpperCase(tr_TR)
            // = "SİNGLETON"（已用 JDK 21 验证）。
            // 现状说明：目前三个作用域里只有 SINGLETON 含 i，而解析失败恰好也退回
            // SINGLETON，所以**暂时观察不到**错误行为。但这是个陷阱 —— 一旦将来
            // 新增含 i 且回退值不同的作用域，土耳其语环境下就会静默降级。
            return Component.Scope.valueOf(scope.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Component.Scope.SINGLETON;
        }
    }

    private static Constructor<?> resolveConstructor(Class<?> clazz) {
        Constructor<?>[] constructors = clazz.getDeclaredConstructors();
        // 1. 优先选择带 @Inject 的构造器 (JSR-330 或框架自带注解)
        for (Constructor<?> ctor : constructors) {
            if (ctor.isAnnotationPresent(jakarta.inject.Inject.class)
                    || ctor.isAnnotationPresent(com.jvfault.core.annotation.Inject.class)) {
                return ctor;
            }
        }
        // 2. 其次选择无参构造器 (任意可见性，实例化时会 setAccessible)
        for (Constructor<?> ctor : constructors) {
            if (ctor.getParameterCount() == 0) {
                return ctor;
            }
        }
        // 3. 最后选择参数最多的构造器 (贪婪模式)
        Constructor<?> best = null;
        for (Constructor<?> ctor : constructors) {
            if (best == null || ctor.getParameterCount() > best.getParameterCount()) {
                best = ctor;
            }
        }
        return best;
    }

    // ============ Getter/Setter ============

    public String getName() { return name; }
    public Class<?> getBeanClass() { return beanClass; }
    public Class<?> getFactoryClass() { return factoryClass; }
    public Method getFactoryMethod() { return factoryMethod; }
    public Constructor<?> getConstructor() { return constructor; }

    public Component.Scope getScope() { return scope; }
    public void setScope(Component.Scope scope) { this.scope = scope; }

    public boolean isLazyInit() { return lazyInit; }
    public void setLazyInit(boolean lazyInit) { this.lazyInit = lazyInit; }

    public boolean isPrimary() { return primary; }
    public void setPrimary(boolean primary) { this.primary = primary; }

    public boolean isAutowireCandidate() { return autowireCandidate; }
    public void setAutowireCandidate(boolean autowireCandidate) { this.autowireCandidate = autowireCandidate; }

    public String getInitMethodName() { return initMethodName; }
    public void setInitMethodName(String initMethodName) { this.initMethodName = initMethodName; }

    public String getDestroyMethodName() { return destroyMethodName; }
    public void setDestroyMethodName(String destroyMethodName) { this.destroyMethodName = destroyMethodName; }

    public List<DependencyDescriptor> getConstructorDependencies() { return constructorDependencies; }
    public List<DependencyDescriptor> getFieldDependencies() { return fieldDependencies; }
    public List<DependencyDescriptor> getMethodDependencies() { return methodDependencies; }

    public Set<String> getQualifiers() { return qualifiers; }
    public void addQualifier(String qualifier) { qualifiers.add(qualifier); }

    public Map<Class<? extends Annotation>, Annotation> getAnnotations() { return annotations; }
    public <A extends Annotation> A getAnnotation(Class<A> annotationType) {
        return annotationType.cast(annotations.get(annotationType));
    }
    public void addAnnotation(Annotation annotation) {
        annotations.put(annotation.annotationType(), annotation);
    }

    public Object getInstance() { return instance; }
    public void setInstance(Object instance) { this.instance = instance; }

    public boolean isInitialized() { return initialized; }
    public void setInitialized(boolean initialized) { this.initialized = initialized; }

    public FactoryBean<?> getFactoryBean() { return factoryBean; }
    public void setFactoryBean(FactoryBean<?> factoryBean) { this.factoryBean = factoryBean; }

    // ============ 依赖描述符 ============

    public static class DependencyDescriptor {
        private final int index;           // 参数索引 (构造器/方法)
        private final Class<?> type;       // 依赖类型
        private final String qualifier;    // 限定符 (@Named/@Qualifier)
        private final boolean required;    // 是否必须
        private final Annotation[] annotations; // 完整注解数组

        public DependencyDescriptor(int index, Class<?> type, String qualifier, boolean required, Annotation[] annotations) {
            this.index = index;
            this.type = type;
            this.qualifier = qualifier;
            this.required = required;
            this.annotations = annotations;
        }

        public int getIndex() { return index; }
        public Class<?> getType() { return type; }
        public String getQualifier() { return qualifier; }
        public boolean isRequired() { return required; }
        public Annotation[] getAnnotations() { return annotations; }
    }
}