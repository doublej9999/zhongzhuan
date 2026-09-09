package com.example.transfer.scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ScanScheduler 调度器单测：独立周期、异常隔离、启停控制。
 */
class ScanSchedulerTest {

    @TempDir
    Path tempDir;

    @Test
    void schedulerExecutesPeriodicallyAndIsolatesExceptions() throws InterruptedException {
        DirectoryScanner mockScanner = mock(DirectoryScanner.class);

        CountDownLatch latchDir1 = new CountDownLatch(2);
        CountDownLatch latchDir2 = new CountDownLatch(2);

        // 模拟 dir1 正常扫描
        when(mockScanner.scan(any(DirectoryScanTarget.class))).thenAnswer(invocation -> {
            DirectoryScanTarget target = invocation.getArgument(0);
            if ("dir-1".equals(target.directoryCode())) {
                latchDir1.countDown();
                return ScanResult.success(1, 0, 0, 0, 0, 0);
            } else {
                // 模拟 dir2 抛出致命异常，验证不会击垮调度线程池，也不会影响 dir1
                latchDir2.countDown();
                throw new RuntimeException("Simulated IO crash on dir-2");
            }
        });

        ScanScheduler scheduler = new ScanScheduler(mockScanner);

        DirectoryScanTarget target1 = new DirectoryScanTarget(
                1L, 1L, 1L, "dir-1", "cs-1", tempDir, List.of(), 1L, true);
        DirectoryScanTarget target2 = new DirectoryScanTarget(
                2L, 1L, 1L, "dir-2", "cs-1", tempDir, List.of(), 1L, true);

        scheduler.schedule(target1);
        scheduler.schedule(target2);

        assertEquals(2, scheduler.scheduledCount());

        // 等待两个目录均被调度至少 2 次
        assertTrue(latchDir1.await(5, TimeUnit.SECONDS), "dir1 应该周期性执行");
        assertTrue(latchDir2.await(5, TimeUnit.SECONDS), "dir2 异常后应该继续周期性重试，而不是终止");

        scheduler.cancel(1L);
        assertEquals(1, scheduler.scheduledCount());

        scheduler.shutdown();
        assertEquals(0, scheduler.scheduledCount());
    }
}
