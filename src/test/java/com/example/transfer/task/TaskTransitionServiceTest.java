package com.example.transfer.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.transfer.repository.AttemptOutcome;
import com.example.transfer.repository.AttemptType;
import com.example.transfer.repository.CustomerSpaceRepository;
import com.example.transfer.repository.CustomerSpaceRow;
import com.example.transfer.repository.DirectoryRepository;
import com.example.transfer.repository.DirectoryRow;
import com.example.transfer.repository.FileRepository;
import com.example.transfer.repository.FileRow;
import com.example.transfer.repository.FileVersionRepository;
import com.example.transfer.repository.FileVersionRow;
import com.example.transfer.repository.NasRepository;
import com.example.transfer.repository.NasRow;
import com.example.transfer.repository.TaskStatus;
import com.example.transfer.repository.TransferEventRepository;
import com.example.transfer.repository.TransferEventRow;
import com.example.transfer.repository.TransferTaskRepository;
import com.example.transfer.repository.TransferTaskRow;

/**
 * 状态迁移集成测试：真实 PostgreSQL + Flyway 已建表。
 *
 * <p>覆盖 04 §2 的 T1–T21（T1=初始创建、T22/T23=禁止已由
 * {@link TaskStateMachineTest} 覆盖）、非法迁移拒绝、条件更新冲突、事务原子回滚、
 * 双线程并发迁移。每个用例自建 fixture 并在 finally 中按 FK 逆序清理。</p>
 */
@SpringBootTest
class TaskTransitionServiceTest {

    @Autowired
    private TaskTransitionService transitionService;

    @Autowired
    private TaskStateMachine stateMachine;

    @Autowired
    private NasRepository nasRepository;

    @Autowired
    private CustomerSpaceRepository customerSpaceRepository;

    @Autowired
    private DirectoryRepository directoryRepository;

    @Autowired
    private FileRepository fileRepository;

    @Autowired
    private FileVersionRepository fileVersionRepository;

    @Autowired
    private TransferTaskRepository transferTaskRepository;

    @Autowired
    private TransferEventRepository transferEventRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final AtomicInteger seq = new AtomicInteger();

    private record Fixture(long taskId, long fileVersionId, long directoryId, long customerSpaceId,
            long fileId, long nasId) {
    }

    private String suffix() {
        return "p3-" + System.nanoTime() + "-" + seq.incrementAndGet();
    }

    private Fixture fixture(TaskStatus status, OffsetDateTime s3UploadedAt, String workerId,
            OffsetDateTime leaseUntil, Integer retryCount, OffsetDateTime nextRetryAt) {
        String s = suffix();
        long nas = nasRepository.insert(new NasRow(null, "nas-" + s, "/mnt/" + s, true, null, null));
        long cs = customerSpaceRepository.insert(
                new CustomerSpaceRow(null, "cs-" + s, "customer-" + s, true, null, null));
        long dir = directoryRepository.insert(new DirectoryRow(null, nas, cs, "dir-" + s,
                "d/" + s, "[\"csv\"]", 3600L, 2, true, null, null, null));
        long file = fileRepository.insert(
                new FileRow(null, nas, dir, "f-" + s + ".csv", null, null));
        OffsetDateTime mtime = OffsetDateTime.now();
        long fv = fileVersionRepository.insert(new FileVersionRow(null, file, 1, 123L, mtime,
                "fp-" + s, null, null, null, null));
        long task = transferTaskRepository.insert(new TransferTaskRow(null, fv, file, 1, dir, cs,
                "f-" + s + ".csv", 123L, mtime, "it-bucket", "it-key-" + s, s3UploadedAt,
                "route-" + s, "v1", status, 0,
                retryCount != null ? retryCount : 0,
                nextRetryAt, workerId,
                workerId != null ? OffsetDateTime.now() : null,
                leaseUntil, null, null, null, null, null, null, null, null));
        return new Fixture(task, fv, dir, cs, file, nas);
    }

    private Fixture fixture(TaskStatus status) {
        return fixture(status, null, null, null, 0, null);
    }

    private void cleanup(Fixture f) {
        jdbcTemplate.update("DELETE FROM transfer_event WHERE transfer_task_id = ?", f.taskId());
        jdbcTemplate.update("DELETE FROM transfer_attempt WHERE transfer_task_id = ?", f.taskId());
        jdbcTemplate.update("DELETE FROM transfer_task WHERE id = ?", f.taskId());
        jdbcTemplate.update("DELETE FROM file_version WHERE id = ?", f.fileVersionId());
        jdbcTemplate.update("DELETE FROM file WHERE id = ?", f.fileId());
        jdbcTemplate.update("DELETE FROM directory WHERE id = ?", f.directoryId());
        jdbcTemplate.update("DELETE FROM customer_space WHERE id = ?", f.customerSpaceId());
        jdbcTemplate.update("DELETE FROM nas WHERE id = ?", f.nasId());
    }

    private TransferTaskRow task(long id) {
        return transferTaskRepository.findById(id).orElseThrow();
    }

    private int eventCount(long taskId) {
        return transferEventRepository.findByTransferTaskId(taskId).size();
    }

    private OffsetDateTime leaseFuture() {
        return OffsetDateTime.now().plusMinutes(5);
    }

    private AttemptWrite attempt(AttemptType type, int no, AttemptOutcome outcome, boolean success,
            Integer httpStatus, String errorCode) {
        OffsetDateTime now = OffsetDateTime.now();
        return new AttemptWrite(type, no, now, now, success, outcome, httpStatus, errorCode,
                null, "gw-1", 120L);
    }

    // ---------------- T2 / T3 / T4：发现与稳定 ----------------

    @Test
    void t2_discoveredToStabilityCheck() {
        Fixture f = fixture(TaskStatus.DISCOVERED);
        try {
            transitionService.transition(new TransitionRequest(f.taskId(), "T2",
                    TaskStatus.DISCOVERED, null, null, null, null, null, null, null, null, null, null));
            assertEquals(TaskStatus.STABILITY_CHECK, task(f.taskId()).status());
            List<TransferEventRow> events = transferEventRepository.findByTransferTaskId(f.taskId());
            assertEquals(1, events.size());
            assertEquals("DISCOVERED", events.get(0).fromStatus());
            assertEquals("STABILITY_CHECK", events.get(0).toStatus());
        } finally {
            cleanup(f);
        }
    }

    @Test
    void t3_selfStabilityLoopKeepsStatus() {
        Fixture f = fixture(TaskStatus.STABILITY_CHECK);
        try {
            transitionService.transition(new TransitionRequest(f.taskId(), "T3",
                    TaskStatus.STABILITY_CHECK, null, null, null, null, null, null, null, null,
                    "fingerprint-changed", null));
            assertEquals(TaskStatus.STABILITY_CHECK, task(f.taskId()).status());
            TransferEventRow last = lastEvent(f);
            assertEquals("STABILITY_CHECK", last.toStatus());
        } finally {
            cleanup(f);
        }
    }

    @Test
    void t4_stabilityToReadyWritesStableAt() {
        Fixture f = fixture(TaskStatus.STABILITY_CHECK);
        try {
            OffsetDateTime stableAt = OffsetDateTime.now();
            transitionService.transition(new TransitionRequest(f.taskId(), "T4",
                    TaskStatus.STABILITY_CHECK, null, null, null, null, null, null, stableAt,
                    null, null, null));
            TransferTaskRow task = task(f.taskId());
            assertEquals(TaskStatus.READY, task.status());
            FileVersionRow fv = fileVersionRepository.findById(f.fileVersionId()).orElseThrow();
            assertNotNull(fv.stableAt());
        } finally {
            cleanup(f);
        }
    }

    // ---------------- T5 / T7 / T20 / T21：取消与 supersede ----------------

    @Test
    void t5_cancelFromStabilityCheck() {
        Fixture f = fixture(TaskStatus.STABILITY_CHECK);
        try {
            transitionService.transition(TransitionRequest.cancel(f.taskId(), "T5",
                    TaskStatus.STABILITY_CHECK, "FILE_MISSING", null));
            TransferTaskRow task = task(f.taskId());
            assertEquals(TaskStatus.CANCELLED, task.status());
            assertEquals("FILE_MISSING", task.cancelReason());
            assertNotNull(task.cancelledAt());
        } finally {
            cleanup(f);
        }
    }

    @Test
    void t7_cancelFromReady() {
        Fixture f = fixture(TaskStatus.READY);
        try {
            transitionService.transition(TransitionRequest.cancel(f.taskId(), "T7",
                    TaskStatus.READY, "DIRECTORY_DISABLED", null));
            assertEquals(TaskStatus.CANCELLED, task(f.taskId()).status());
        } finally {
            cleanup(f);
        }
    }

    @Test
    void t20_supersedeWaitingRetry() {
        Fixture f = fixture(TaskStatus.WAITING_RETRY, null, null, null, 5,
                OffsetDateTime.now().plusHours(1));
        try {
            transitionService.transition(TransitionRequest.cancel(f.taskId(), "T20",
                    TaskStatus.WAITING_RETRY, "SUPERSEDED_BY_NEWER_VERSION", 2));
            TransferTaskRow task = task(f.taskId());
            assertEquals(TaskStatus.CANCELLED, task.status());
            assertEquals("SUPERSEDED_BY_NEWER_VERSION", task.cancelReason());
            assertEquals(2, task.supersededByVersionNo());
        } finally {
            cleanup(f);
        }
    }

    @Test
    void t21_supersedeUnifiedEntryFromAllFourStates() {
        for (TaskStatus from : List.of(TaskStatus.DISCOVERED, TaskStatus.STABILITY_CHECK,
                TaskStatus.READY, TaskStatus.WAITING_RETRY)) {
            Fixture f = fixture(from, null, null, null, 1, null);
            try {
                transitionService.transition(TransitionRequest.cancel(f.taskId(), "T21", from,
                        "SUPERSEDED_BY_NEWER_VERSION", 3));
                TransferTaskRow task = task(f.taskId());
                assertEquals(TaskStatus.CANCELLED, task.status());
                assertEquals("SUPERSEDED_BY_NEWER_VERSION", task.cancelReason());
                assertEquals(3, task.supersededByVersionNo());
                assertNull(task.workerId());
                assertNull(task.leaseUntil());
            } finally {
                cleanup(f);
            }
        }
    }

    // ---------------- T6 / T17 / T18：claim ----------------

    @Test
    void t6_claimReadyToUploading() {
        Fixture f = fixture(TaskStatus.READY);
        try {
            OffsetDateTime lease = leaseFuture();
            transitionService.transition(
                    TransitionRequest.claim(f.taskId(), "T6", TaskStatus.READY, "worker-1", lease));
            TransferTaskRow task = task(f.taskId());
            assertEquals(TaskStatus.UPLOADING, task.status());
            assertEquals("worker-1", task.workerId());
            assertNotNull(task.claimedAt());
            assertNotNull(task.leaseUntil());
            assertNotNull(task.startedAt());
        } finally {
            cleanup(f);
        }
    }

    @Test
    void t17_claimWaitingRetryToDelivering() {
        Fixture f = fixture(TaskStatus.WAITING_RETRY, OffsetDateTime.now(), null, null, 2, null);
        try {
            OffsetDateTime lease = leaseFuture();
            transitionService.transition(TransitionRequest.claim(f.taskId(), "T17",
                    TaskStatus.WAITING_RETRY, "worker-2", lease));
            TransferTaskRow task = task(f.taskId());
            assertEquals(TaskStatus.GATEWAY_DELIVERING, task.status());
            assertEquals("worker-2", task.workerId());
            assertNull(task.nextRetryAt());
        } finally {
            cleanup(f);
        }
    }

    @Test
    void t18_claimWaitingRetryToUploading() {
        Fixture f = fixture(TaskStatus.WAITING_RETRY, null, null, null, 2, null);
        try {
            OffsetDateTime lease = leaseFuture();
            transitionService.transition(TransitionRequest.claim(f.taskId(), "T18",
                    TaskStatus.WAITING_RETRY, "worker-3", lease));
            assertEquals(TaskStatus.UPLOADING, task(f.taskId()).status());
        } finally {
            cleanup(f);
        }
    }

    // ---------------- T8 / T9 / T10：S3 阶段 ----------------

    @Test
    void t8_uploadingToS3Uploaded() {
        Fixture f = fixture(TaskStatus.UPLOADING, null, "w1", leaseFuture(), 0, null);
        try {
            transitionService.transition(TransitionRequest.s3Committed(f.taskId(), OffsetDateTime.now()));
            TransferTaskRow task = task(f.taskId());
            assertEquals(TaskStatus.S3_UPLOADED, task.status());
            assertNotNull(task.s3UploadedAt());
        } finally {
            cleanup(f);
        }
    }

    @Test
    void t9_s3FailureToWaitingRetry() {
        Fixture f = fixture(TaskStatus.UPLOADING, null, "w1", leaseFuture(), 0, null);
        try {
            OffsetDateTime backoff = OffsetDateTime.now().plusSeconds(30);
            transitionService.transition(TransitionRequest.failure(f.taskId(), "T9",
                    TaskStatus.UPLOADING, backoff,
                    attempt(AttemptType.S3_UPLOAD, 1, AttemptOutcome.FAILURE, false, 503, "S3_5XX")));
            TransferTaskRow task = task(f.taskId());
            assertEquals(TaskStatus.WAITING_RETRY, task.status());
            assertEquals(1, task.retryCount());
            assertNotNull(task.nextRetryAt());
            assertNull(task.workerId());
            assertNull(task.leaseUntil());
            assertEquals(1, countAttempts(f.taskId()));
        } finally {
            cleanup(f);
        }
    }

    @Test
    void t10_recoveryUploadingToReady() {
        Fixture f = fixture(TaskStatus.UPLOADING, null, "stale-worker", OffsetDateTime.now().minusMinutes(1), 0, null);
        try {
            transitionService.transition(TransitionRequest.recovery(f.taskId(), "T10", TaskStatus.UPLOADING));
            TransferTaskRow task = task(f.taskId());
            assertEquals(TaskStatus.READY, task.status());
            assertNull(task.workerId());
            assertNull(task.leaseUntil());
        } finally {
            cleanup(f);
        }
    }

    // ---------------- T11 / T12 / T13：投递阶段 ----------------

    @Test
    void t11_sameExecutionContinueKeepsLease() {
        Fixture f = fixture(TaskStatus.S3_UPLOADED, OffsetDateTime.now(), "w1", leaseFuture(), 0, null);
        try {
            transitionService.transition(new TransitionRequest(f.taskId(), "T11",
                    TaskStatus.S3_UPLOADED, null, null, null, null, null, null, null, null, null, null));
            TransferTaskRow task = task(f.taskId());
            assertEquals(TaskStatus.GATEWAY_DELIVERING, task.status());
            assertEquals("w1", task.workerId());
            assertNotNull(task.leaseUntil());
        } finally {
            cleanup(f);
        }
    }

    @Test
    void t12_recoveryReleasesForReclaim() {
        Fixture f = fixture(TaskStatus.S3_UPLOADED, OffsetDateTime.now(), "stale-worker",
                OffsetDateTime.now().minusMinutes(1), 0, null);
        try {
            transitionService.transition(TransitionRequest.recovery(f.taskId(), "T12", TaskStatus.S3_UPLOADED));
            TransferTaskRow task = task(f.taskId());
            assertEquals(TaskStatus.GATEWAY_DELIVERING, task.status());
            assertNull(task.workerId());
            assertNull(task.leaseUntil());
        } finally {
            cleanup(f);
        }
    }

    @Test
    void t13_gatewaySuccessToDelivered() {
        Fixture f = fixture(TaskStatus.GATEWAY_DELIVERING, OffsetDateTime.now(), "w1", leaseFuture(), 0, null);
        try {
            transitionService.transition(TransitionRequest.success(f.taskId(),
                    TaskStatus.GATEWAY_DELIVERING,
                    attempt(AttemptType.GATEWAY_DELIVER, 1, AttemptOutcome.SUCCESS, true, 200, null)));
            TransferTaskRow task = task(f.taskId());
            assertEquals(TaskStatus.DELIVERED, task.status());
            assertNotNull(task.completedAt());
            assertNull(task.workerId());
            assertEquals(1, countAttempts(f.taskId()));
            assertEquals(1, eventCount(f.taskId()));
        } finally {
            cleanup(f);
        }
    }

    @Test
    void t14_t15_t16_gatewayFailuresToWaitingRetry() {
        // T14 明确失败
        Fixture f14 = fixture(TaskStatus.GATEWAY_DELIVERING, OffsetDateTime.now(), "w1", leaseFuture(), 0, null);
        try {
            transitionService.transition(TransitionRequest.failure(f14.taskId(), "T14",
                    TaskStatus.GATEWAY_DELIVERING, OffsetDateTime.now().plusSeconds(30),
                    attempt(AttemptType.GATEWAY_DELIVER, 1, AttemptOutcome.FAILURE, false, 500,
                            "GATEWAY_5XX")));
            assertEquals(TaskStatus.WAITING_RETRY, task(f14.taskId()).status());
        } finally {
            cleanup(f14);
        }
        // T15 结果未知
        Fixture f15 = fixture(TaskStatus.GATEWAY_DELIVERING, OffsetDateTime.now(), "w1", leaseFuture(), 1, null);
        try {
            transitionService.transition(TransitionRequest.failure(f15.taskId(), "T15",
                    TaskStatus.GATEWAY_DELIVERING, OffsetDateTime.now().plusSeconds(30),
                    attempt(AttemptType.GATEWAY_DELIVER, 1, AttemptOutcome.UNKNOWN, false, null,
                            "UNKNOWN_OUTCOME")));
            TransferTaskRow task = task(f15.taskId());
            assertEquals(TaskStatus.WAITING_RETRY, task.status());
            assertEquals(2, task.retryCount());
            assertEquals(1, countAttempts(f15.taskId()));
        } finally {
            cleanup(f15);
        }
        // T16 崩溃恢复
        Fixture f16 = fixture(TaskStatus.GATEWAY_DELIVERING, OffsetDateTime.now(), "stale-worker",
                OffsetDateTime.now().minusMinutes(1), 0, null);
        try {
            transitionService.transition(TransitionRequest.recovery(f16.taskId(), "T16",
                    TaskStatus.GATEWAY_DELIVERING));
            TransferTaskRow task = task(f16.taskId());
            assertEquals(TaskStatus.WAITING_RETRY, task.status());
            assertNull(task.workerId());
        } finally {
            cleanup(f16);
        }
    }

    // ---------------- T19：manual retry ----------------

    @Test
    void t19_manualRetryOperatorAudited() {
        Fixture f = fixture(TaskStatus.WAITING_RETRY, null, null, null, 3, null);
        try {
            transitionService.transition(TransitionRequest.manualRetry(f.taskId(), "ops-zhangsan"));
            TransferTaskRow task = task(f.taskId());
            assertEquals(TaskStatus.READY, task.status());
            TransferEventRow last = lastEvent(f);
            assertEquals("ops-zhangsan", last.operator());
            assertEquals("T19", last.reason());
        } finally {
            cleanup(f);
        }
    }

    // ---------------- 非法 / 冲突 / 事务 / 并发 ----------------

    @Test
    void illegalCodeRejectedWithoutStateChange() {
        Fixture f = fixture(TaskStatus.READY);
        try {
            assertThrows(IllegalTransitionException.class, () -> transitionService.transition(
                    new TransitionRequest(f.taskId(), "T13", TaskStatus.READY, null, null, null,
                            null, null, null, null, null, null, null)));
            assertEquals(TaskStatus.READY, task(f.taskId()).status());
            assertEquals(0, eventCount(f.taskId()));
        } finally {
            cleanup(f);
        }
    }

    @Test
    void staleFromOptimisticConflictRejected() {
        Fixture f = fixture(TaskStatus.READY);
        try {
            OffsetDateTime lease = leaseFuture();
            transitionService.transition(
                    TransitionRequest.claim(f.taskId(), "T6", TaskStatus.READY, "worker-a", lease));
            assertEquals(TaskStatus.UPLOADING, task(f.taskId()).status());
            // 第二次仍以 READY 为乐观前提 → 条件更新 0 行
            assertThrows(ConcurrentTransitionException.class, () -> transitionService.transition(
                    TransitionRequest.claim(f.taskId(), "T6", TaskStatus.READY, "worker-b", lease)));
            TransferTaskRow task = task(f.taskId());
            assertEquals(TaskStatus.UPLOADING, task.status());
            assertEquals("worker-a", task.workerId());
            assertEquals(1, eventCount(f.taskId()));
        } finally {
            cleanup(f);
        }
    }

    @Test
    void transactionIsAtomicOnRollback() {
        Fixture f = fixture(TaskStatus.READY);
        try {
            TransactionTemplate inner = new TransactionTemplate(transactionManager);
            inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            OffsetDateTime lease = leaseFuture();
            assertThrows(RuntimeException.class, () -> inner.executeWithoutResult(status -> {
                transitionService.transition(
                        TransitionRequest.claim(f.taskId(), "T6", TaskStatus.READY, "worker-x", lease));
                throw new IllegalStateException("simulated crash after transition");
            }));
            // 内部事务回滚：状态与事件都必须还原
            TransferTaskRow task = task(f.taskId());
            assertEquals(TaskStatus.READY, task.status());
            assertNull(task.workerId());
            assertEquals(0, eventCount(f.taskId()));
        } finally {
            cleanup(f);
        }
    }

    @Test
    void twoWorkersConcurrentClaimOnlyOneWins() throws Exception {
        Fixture f = fixture(TaskStatus.READY);
        try {
            OffsetDateTime lease = leaseFuture();
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            AtomicInteger success = new AtomicInteger();
            AtomicInteger conflict = new AtomicInteger();
            List<Future<?>> futures = List.of(
                    pool.submit(() -> claimWithLatch(f.taskId(), "worker-a", lease, ready, go, success, conflict)),
                    pool.submit(() -> claimWithLatch(f.taskId(), "worker-b", lease, ready, go, success, conflict)));
            ready.await(10, TimeUnit.SECONDS);
            go.countDown();
            for (Future<?> future : futures) {
                future.get(15, TimeUnit.SECONDS);
            }
            pool.shutdownNow();
            assertEquals(1, success.get(), "并发 claim 只能成功一个");
            assertEquals(1, conflict.get(), "另一个必须拿到 ConcurrentTransitionException");
            TransferTaskRow task = task(f.taskId());
            assertEquals(TaskStatus.UPLOADING, task.status());
            assertTrue(task.workerId().equals("worker-a") || task.workerId().equals("worker-b"));
            assertEquals(1, eventCount(f.taskId()));
        } finally {
            cleanup(f);
        }
    }

    private void claimWithLatch(long taskId, String worker, OffsetDateTime lease,
            CountDownLatch ready, CountDownLatch go, AtomicInteger success, AtomicInteger conflict) {
        ready.countDown();
        try {
            go.await(10, TimeUnit.SECONDS);
            transitionService.transition(
                    TransitionRequest.claim(taskId, "T6", TaskStatus.READY, worker, lease));
            success.incrementAndGet();
        } catch (ConcurrentTransitionException e) {
            conflict.incrementAndGet();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private TransferEventRow lastEvent(Fixture f) {
        List<TransferEventRow> events = transferEventRepository.findByTransferTaskId(f.taskId());
        assertTrue(events.size() >= 1);
        return events.get(events.size() - 1);
    }

    private int countAttempts(long taskId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM transfer_attempt WHERE transfer_task_id = ?", Integer.class,
                taskId);
        return n == null ? 0 : n;
    }
}
