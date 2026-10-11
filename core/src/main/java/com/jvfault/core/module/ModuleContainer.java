package com.jvfault.core.module;

import com.jvfault.core.annotation.Bean;
import com.jvfault.core.annotation.Component;
import com.jvfault.core.container.BeanDefinition;
import com.jvfault.core.container.BeanRegistry;
import com.jvfault.core.container.DefaultBeanRegistry;
import com.jvfault.core.scanner.ModuleScanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 模块容器 - 管理模块加载、依赖解析、Bean 注册的核心编排器
 * 
 * <p>职责：
 * <ul>
 *   <li>模块图构建与拓扑排序</li>
 *   <li>Bean 定义收集与合并 (处理 imports/exports)</li>
 *   <li>循环依赖检测</li>
 *   <li>委托 BeanRegistry 实例化</li>
 * </ul>
 * 
 * @since v0.1.0 (2015)
 * @author jvfault team
 */
public class ModuleContainer {

    private static final Logger log = LoggerFactory.getLogger(ModuleContainer.class);

    private final BeanRegistry beanRegistry;
    private final Map<String, ModuleMetadata> moduleMap = new ConcurrentHashMap<>();
    private final List<ModuleMetadata> sortedModules = new ArrayList<>();
    private final Set<String> globalModules = ConcurrentHashMap.newKeySet();

    public ModuleContainer() {
        this(new DefaultBeanRegistry());
    }

    public ModuleContainer(BeanRegistry beanRegistry) {
        this.beanRegistry = beanRegistry;
    }

    // ============ 核心 API ============

    /**
     * 刷新容器 - 完整的初始化流程
     * 
     * @param rootModuleClass 根模块类 (入口)
     * @param basePackages 扫描基础包
     */
    public void refresh(Class<?> rootModuleClass, String... basePackages) {
        long start = System.nanoTime();
        log.info("Refreshing Jvfault container with root module: {}", rootModuleClass.getName());

        try {
            // 1. 扫描模块
            ModuleScanner scanner = ModuleScanner.create(basePackages)
                    .scanModules(true)
                    .scanComponents(true)
                    .scanConfigurations(true);
            Map<String, ModuleMetadata> scannedModules = scanner.scan();

            // 2. 构建模块图 (从根模块开始递归)
            buildModuleGraph(rootModuleClass, scannedModules);

            // 3. 拓扑排序 (处理依赖顺序)
            topologicalSort();

            // 4. 注册 Bean 定义 (合并 imports/exports)
            registerBeanDefinitions();

            // 5. 实例化单例 Bean
            beanRegistry.initializeSingletons();

            // 6. 调用初始化回调
            invokeInitCallbacks();

            long elapsed = (System.nanoTime() - start) / 1_000_000;
            log.info("Container refreshed in {}ms, {} modules, {} beans", 
                    elapsed, sortedModules.size(), beanRegistry.getBeanNames().size());

        } catch (Exception e) {
            log.error("Container refresh failed", e);
            destroy();
            throw new IllegalStateException("Container refresh failed", e);
        }
    }

    /**
     * 构建模块依赖图
     */
    private void buildModuleGraph(Class<?> rootModuleClass, Map<String, ModuleMetadata> scannedModules) {
        ModuleMetadata root = scannedModules.get(rootModuleClass.getName());
        if (root == null) {
            throw new IllegalArgumentException("Root module not found in scanned modules: " + rootModuleClass.getName());
        }

        // 递归收集所有可达模块
        collectModules(root, scannedModules, new HashSet<>());

        // 验证所有模块都已扫描到
        for (ModuleMetadata module : moduleMap.values()) {
            for (Class<?> importClass : module.getImports()) {
                if (!moduleMap.containsKey(importClass.getName())) {
                    throw new IllegalStateException("Imported module not found: " + importClass.getName() 
                            + " (imported by " + module.getModuleName() + ")");
                }
            }
        }
    }

    private void collectModules(ModuleMetadata module, Map<String, ModuleMetadata> scannedModules, Set<String> visited) {
        String key = module.getModuleKey();
        if (visited.contains(key)) return;
        visited.add(key);

        if (moduleMap.putIfAbsent(key, module) != null) {
            return; // 已处理
        }

        if (module.isGlobal()) {
            globalModules.add(key);
        }

        // 递归处理 imports
        for (Class<?> importClass : module.getImports()) {
            ModuleMetadata imported = scannedModules.get(importClass.getName());
            if (imported == null) {
                throw new IllegalStateException("Module not found: " + importClass.getName());
            }
            module.addImportedModule(imported);
            collectModules(imported, scannedModules, visited);
        }
    }

    /**
     * 拓扑排序 - Kahn 算法
     * 确保依赖模块先于被依赖模块初始化
     */
    private void topologicalSort() {
        // 计算入度
        Map<ModuleMetadata, Integer> inDegree = new HashMap<>();
        for (ModuleMetadata module : moduleMap.values()) {
            inDegree.put(module, 0);
        }
        for (ModuleMetadata module : moduleMap.values()) {
            for (ModuleMetadata imported : module.getImportedModules()) {
                inDegree.put(imported, inDegree.get(imported) + 1);
            }
        }

        // BFS
        Queue<ModuleMetadata> queue = new LinkedList<>();
        for (Map.Entry<ModuleMetadata, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.offer(entry.getKey());
            }
        }

        int order = 0;
        while (!queue.isEmpty()) {
            ModuleMetadata current = queue.poll();
            current.setTopologicalOrder(order++);
            sortedModules.add(current);

            for (ModuleMetadata imported : current.getImportedModules()) {
                int newDegree = inDegree.get(imported) - 1;
                inDegree.put(imported, newDegree);
                if (newDegree == 0) {
                    queue.offer(imported);
                }
            }
        }

        // 检测循环依赖
        if (sortedModules.size() != moduleMap.size()) {
            List<String> cycle = findCycle();
            throw new IllegalStateException("Circular module dependency detected: " + cycle);
        }

        log.debug("Topological sort completed: {}", 
                sortedModules.stream().map(ModuleMetadata::getModuleName).collect(Collectors.joining(" -> ")));
    }

    private List<String> findCycle() {
        // 简化的循环检测，实际可用 Tarjan 算法
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        List<String> path = new ArrayList<>();
        
        for (ModuleMetadata module : moduleMap.values()) {
            if (dfsCycle(module, visiting, visited, path)) {
                return new ArrayList<>(path.subList(path.indexOf(module.getModuleKey()), path.size()));
            }
        }
        return Collections.emptyList();
    }

    private boolean dfsCycle(ModuleMetadata module, Set<String> visiting, Set<String> visited, List<String> path) {
        String key = module.getModuleKey();
        if (visiting.contains(key)) {
            path.add(key);
            return true;
        }
        if (visited.contains(key)) return false;

        visiting.add(key);
        path.add(key);

        for (ModuleMetadata imported : module.getImportedModules()) {
            if (dfsCycle(imported, visiting, visited, path)) {
                return true;
            }
        }

        visiting.remove(key);
        visited.add(key);
        path.remove(path.size() - 1);
        return false;
    }

    /**
     * 注册所有模块的 Bean 定义到 BeanRegistry
     * 处理 exports 传递，global 模块自动导出
     */
    private void registerBeanDefinitions() {
        // 先注册 global 模块的 providers
        for (String globalKey : globalModules) {
            ModuleMetadata globalModule = moduleMap.get(globalKey);
            if (globalModule != null) {
                registerModuleProviders(globalModule, true);
            }
        }

        // 按拓扑顺序注册非 global 模块
        for (ModuleMetadata module : sortedModules) {
            if (!module.isGlobal()) {
                registerModuleProviders(module, false);
            }
        }
    }

    private void registerModuleProviders(ModuleMetadata module, boolean isGlobal) {
        // 注册 providers
        for (Class<?> providerClass : module.getProviders()) {
            registerProviderClass(module, providerClass, isGlobal);
        }

        // 注册 controllers (延迟到 Web 模块处理)
        for (Class<?> controllerClass : module.getControllers()) {
            BeanDefinition def = BeanDefinition.fromComponent(controllerClass);
            module.getControllerDefinitions().put(def.getName(), def);
        }
    }

    /**
     * 注册单个 Provider 类。
     * 若该类是 @Configuration 类，同时注册其中的 @Bean 方法。
     */
    private void registerProviderClass(ModuleMetadata module, Class<?> providerClass, boolean isGlobal) {
        BeanDefinition def = BeanDefinition.fromComponent(providerClass);
        String beanName = def.getName();

        // 检查是否已注册 (global 模块优先)
        if (beanRegistry.containsBean(beanName) && !isGlobal) {
            log.debug("Bean '{}' already registered by global module, skipping", beanName);
        } else {
            beanRegistry.registerBean(providerClass, def);
            module.getProviderDefinitions().put(beanName, def);
        }

        // @Configuration 类：注册其 @Bean 方法
        com.jvfault.core.annotation.Configuration config =
                providerClass.getAnnotation(com.jvfault.core.annotation.Configuration.class);
        if (config != null) {
            for (java.lang.reflect.Method method : providerClass.getDeclaredMethods()) {
                Bean beanAnn = method.getAnnotation(Bean.class);
                if (beanAnn == null) {
                    continue;
                }
                BeanDefinition beanDef = BeanDefinition.fromBeanMethod(providerClass, method);
                if (beanRegistry.containsBean(beanDef.getName())) {
                    log.debug("@Bean '{}' already registered, skipping", beanDef.getName());
                    continue;
                }
                beanRegistry.registerBean(method.getReturnType(), beanDef);
                module.getProviderDefinitions().put(beanDef.getName(), beanDef);
                log.debug("Registered @Bean '{}' from {}", beanDef.getName(), providerClass.getSimpleName());
            }
        }
    }

    /**
     * 标记初始化完成。
     * @PostConstruct 与 initMethod 已由 DefaultBeanRegistry 在创建时调用（初始化序列），
     * 这里仅做最终标记，避免重复回调。
     */
    private void invokeInitCallbacks() {
        for (ModuleMetadata module : sortedModules) {
            for (BeanDefinition def : module.getProviderDefinitions().values()) {
                if (def.getInstance() != null) {
                    def.setInitialized(true);
                }
            }
        }
    }

    // ============ 生命周期 ============

    /**
     * 关闭容器 - 销毁所有 Bean
     */
    public void destroy() {
        log.info("Destroying Jvfault container...");
        // 反序销毁
        List<ModuleMetadata> reverse = new ArrayList<>(sortedModules);
        Collections.reverse(reverse);
        
        for (ModuleMetadata module : reverse) {
            for (BeanDefinition def : module.getProviderDefinitions().values()) {
                Object instance = def.getInstance();
                if (instance == null) continue;
                // REQUEST 作用域实例由请求作用域生命周期（enter/exit）负责销毁，
                // 容器级销毁不再调一次 @PreDestroy，否则会双调用。
                if (def.getScope() == Component.Scope.REQUEST) continue;

                // @PreDestroy
                try {
                    for (Method method : instance.getClass().getDeclaredMethods()) {
                        if (method.isAnnotationPresent(jakarta.annotation.PreDestroy.class)) {
                            method.setAccessible(true);
                            method.invoke(instance);
                        }
                    }
                } catch (Exception e) {
                    log.error("Failed to invoke @PreDestroy on {}", def.getName(), e);
                }

                // @Bean destroyMethod
                String destroyMethod = def.getDestroyMethodName();
                if (destroyMethod != null && !destroyMethod.isEmpty() && !"(inferred)".equals(destroyMethod)) {
                    try {
                        Method method = instance.getClass().getMethod(destroyMethod);
                        method.setAccessible(true);
                        method.invoke(instance);
                    } catch (NoSuchMethodException ignored) {
                        // 与 initMethod 处理保持一致：方法不存在即视为未配置销毁回调，
                        // 静默跳过；执行失败则由下面 catch 记 error（销毁期异常不应中断
                        // 整个容器关闭流程，所以只记不抛）。
                    } catch (Exception e) {
                        log.error("Failed to invoke destroyMethod '{}' on {}", destroyMethod, def.getName(), e);
                    }
                }
            }
        }

        beanRegistry.destroySingletons();
        moduleMap.clear();
        sortedModules.clear();
        globalModules.clear();
        log.info("Container destroyed");
    }

    // ============ Getters ============

    public BeanRegistry getBeanRegistry() { return beanRegistry; }
    public Map<String, ModuleMetadata> getModuleMap() { return Collections.unmodifiableMap(moduleMap); }
    public List<ModuleMetadata> getSortedModules() { return Collections.unmodifiableList(sortedModules); }
    public ModuleMetadata getRootModule() { 
        return sortedModules.isEmpty() ? null : sortedModules.get(0); 
    }
}