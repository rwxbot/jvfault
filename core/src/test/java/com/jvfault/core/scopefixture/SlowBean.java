package com.jvfault.core.scopefixture;

import com.jvfault.core.annotation.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 构造很慢的单例组件：用来把「check-then-act」竞态窗口放大到可稳定复现的程度。
 *
 * <p>刻意<b>不</b>通过容器启动的饿汉初始化来创建 —— 见
 * {@code SingletonCreationRaceTest}：并发 {@code getBean} 是唯一的创建入口，
 * 这样测试才不会「因为启动时已经建好」而假绿。
 */
@Component
public class SlowBean {

    public static final AtomicInteger CREATED = new AtomicInteger();

    public SlowBean() {
        CREATED.incrementAndGet();
        try {
            Thread.sleep(400);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
