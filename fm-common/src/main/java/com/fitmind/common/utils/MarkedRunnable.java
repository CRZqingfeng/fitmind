package com.fitmind.common.utils;

/**
 * 带标记的 Runnable。
 *
 * <p>配合自定义 ThreadFactory 使用，可以让线程池创建出来的线程带上
 * 「任务标记」名称，便于线上排查问题（jstack 时一眼看出是哪个任务）。
 * 例如网关加载公钥的线程就叫 {@code AuthFetchJwkThread}。</p>
 */
public class MarkedRunnable implements Runnable {

    private final Runnable runnable;

    /** 线程标记 */
    private final Object mark;

    public MarkedRunnable(Runnable runnable) {
        this(null, runnable);
    }

    public MarkedRunnable(Object mark, Runnable runnable) {
        this.mark = mark;
        this.runnable = runnable;
    }

    public Object getMark() {
        return mark;
    }

    @Override
    public void run() {
        runnable.run();
    }
}
