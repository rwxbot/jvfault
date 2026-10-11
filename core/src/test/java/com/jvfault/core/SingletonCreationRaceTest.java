package com.jvfault.core;

import com.jvfault.core.container.BeanDefinition;
import com.jvfault.core.container.DefaultBeanRegistry;
import com.jvfault.core.scopefixture.SlowBean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 单例创建的「检查 → 创建 → 缓存」原子性回归。
 *
 * <p>对应阿里评审清单里的 <b>Race conditions: check-then-act</b>：
 * 旧实现无锁，多个线程同时读到 null 就会各自创建 —— 单例被构造两次，
 * 不同调用方拿到不同实例（与类 Javadoc 宣称的「线程安全」不符）。
 */
@DisplayName("Core 单例创建原子性")
class SingletonCreationRaceTest {

    @BeforeEach
    void reset() {
        SlowBean.CREATED.set(0);
    }

    @Test
    @DisplayName("并发解析单例：只创建一次，且所有线程拿到同一实例")
    void concurrentGetBeanCreatesSingletonOnce() throws Exception {
        // 直接用裸 registry：不触发 initializeSingletons()，
        // 保证并发 getBean 是唯一创建入口（否则会因启动时已建好而假绿）。
        DefaultBeanRegistry registry = new DefaultBeanRegistry();
        registry.registerBean(SlowBean.class, BeanDefinition.fromComponent(SlowBean.class));

        int threads = 4;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<SlowBean>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return registry.getBean(SlowBean.class);
                }));
            }
            start.countDown();

            List<SlowBean> beans = new ArrayList<>();
            for (Future<SlowBean> f : futures) {
                beans.add(f.get());
            }

            SlowBean first = beans.get(0);
            for (SlowBean b : beans) {
                assertSame(first, b, "并发解析必须返回同一个实例（单例语义）");
            }
            assertEquals(1, SlowBean.CREATED.get(),
                    "单例只能被创建一次（多次创建说明 check-then-act 竞态复现）");
        } finally {
            pool.shutdownNow();
        }
    }
}
