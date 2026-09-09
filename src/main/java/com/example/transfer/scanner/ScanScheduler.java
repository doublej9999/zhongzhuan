package com.example.transfer.scanner;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

/**
 * 多目录独立周期扫描调度器：
 *
 * <p><strong>核心原则（提示词 §五、§十二）</strong>：
 * <ul>
 *   <li>每个 directory 拥有独立的扫描周期，彼此隔离；</li>
 *   <li>单个目录发生异常（NAS 不可达/IO 异常）绝不影响其他目录；</li>
 *   <li>调度器不会因为单次异常而永久停止；</li>
 *   <li>采用固定延迟（scheduleWithFixedDelay），防止单次耗时过长导致自我重叠。</li>
 * </ul>
 */
@Component
public class ScanScheduler {

    private static final Logger log = LoggerFactory.getLogger(ScanScheduler.class);

    private final DirectoryScanner scanner;
    private final ScheduledExecutorService executor;
    private final Map<Long, ScheduledFuture<?>> tasks = new ConcurrentHashMap<>();

    public ScanScheduler(DirectoryScanner scanner) {
        this.scanner = scanner;
        this.executor = Executors.newScheduledThreadPool(4, r -> {
            Thread t = new Thread(r, "scan-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 注册并启动一个目录的独立定时扫描任务。
     *
     * @param target 扫描目标
     */
    public synchronized void schedule(DirectoryScanTarget target) {
        Objects.requireNonNull(target, "target 必填");
        cancel(target.directoryId());

        if (!target.enabled()) {
            log.info("目录 [{}] 已禁用，不予调度", target.directoryCode());
            return;
        }

        long intervalSec = Math.max(1L, target.scanIntervalSec());
        log.info("注册目录定时扫描: directoryCode={}, intervalSec={}, path={}",
                target.directoryCode(), intervalSec, target.rootPath());

        ScheduledFuture<?> future = executor.scheduleWithFixedDelay(() -> {
            try {
                ScanResult result = scanner.scan(target);
                log.info("目录 [{}] 扫描完成: discovered={}, stable={}, modified={}, vanished={}, unchanged={}, newVersion={}, success={}",
                        target.directoryCode(),
                        result.discoveredCount(),
                        result.stableCount(),
                        result.modifiedCount(),
                        result.vanishedCount(),
                        result.unchangedCount(),
                        result.newVersionCount(),
                        result.success());
            } catch (Throwable t) {
                // 异常隔离：绝不抛出，确保 Scheduler 周期不被吞噬终止
                log.error("目录 [{}] 周期扫描异常（本目录将在下周期重试）: {}",
                        target.directoryCode(), t.getMessage(), t);
            }
        }, 0L, intervalSec, TimeUnit.SECONDS);

        tasks.put(target.directoryId(), future);
    }

    /**
     * 取消指定目录的定时扫描任务。
     */
    public synchronized void cancel(long directoryId) {
        ScheduledFuture<?> future = tasks.remove(directoryId);
        if (future != null) {
            future.cancel(false);
        }
    }

    /**
     * 获取当前正在调度的目录任务数。
     */
    public int scheduledCount() {
        return tasks.size();
    }

    @PreDestroy
    public synchronized void shutdown() {
        log.info("正在关闭 ScanScheduler 调度器...");
        tasks.values().forEach(f -> f.cancel(false));
        tasks.clear();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
