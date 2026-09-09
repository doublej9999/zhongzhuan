package com.example.transfer.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * repository 层集成测试：在真实 PostgreSQL（Flyway 已迁移的 V1 schema）上验证
 * 8 张表「插入 → findById 回读」以及各唯一约束 / FK / CHECK 的拒绝路径。
 * 每个测试方法运行于独立事务，结束时整体回滚，不残留历史数据。
 */
@SpringBootTest
@Transactional
class RepositoryIntegrationTest {

    private static final String IT_BUCKET = "it-bucket";
    private static final String IT_CONFIG_VERSION = "v1";

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
    private TransferAttemptRepository transferAttemptRepository;
    @Autowired
    private TransferEventRepository transferEventRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** fixture 链返回的主键集合（由 chain() 一次插入得到）。 */
    private record Chain(long nasId, long customerSpaceId, long directoryId,
                         long fileId, long fileVersionId, long taskId) {
    }

    /**
     * 构造 fixture：nas → customer_space → directory → file → file_version(稳定)
     * → transfer_task(READY)。所有唯一字符串用 nanoTime 后缀，避免与历史残留冲突
     * （本测试整体回滚，正常也不会有残留）。
     */
    private Chain chain() {
        String suffix = Long.toUnsignedString(System.nanoTime());
        OffsetDateTime now = OffsetDateTime.now();

        long nasId = nasRepository.insert(new NasRow(
                null, "nas-" + suffix, "/mnt/it-" + suffix, true, null, null));
        long customerSpaceId = customerSpaceRepository.insert(new CustomerSpaceRow(
                null, "cs-" + suffix, "客户空间 it-" + suffix, true, null, null));
        long directoryId = directoryRepository.insert(new DirectoryRow(
                null, nasId, customerSpaceId, "dir-" + suffix, "/data/it-" + suffix,
                "[\"zip\",\"xml\"]", 60L, 4, true, null, null, null));
        long fileId = fileRepository.insert(new FileRow(
                null, nasId, directoryId, "x/it-" + suffix + ".csv", null, null));
        long fileVersionId = fileVersionRepository.insert(new FileVersionRow(
                null, fileId, 1, 123L, now, "fp-" + suffix, null, now, null, null));
        long taskId = transferTaskRepository.insert(new TransferTaskRow(
                null, fileVersionId, fileId, 1, directoryId, customerSpaceId,
                "dir/x-" + suffix + ".csv", 123L, now,
                IT_BUCKET, "it-key-" + suffix, null,
                "route-" + suffix, IT_CONFIG_VERSION, TaskStatus.READY, 0, 0,
                null, null, null, null, null, null, null, null, null, null, null, null));
        return new Chain(nasId, customerSpaceId, directoryId, fileId, fileVersionId, taskId);
    }

    @Test
    void fullChainRoundTrip() {
        Chain c = chain();

        // nas
        NasRow nas = nasRepository.findById(c.nasId()).orElseThrow();
        assertEquals(c.nasId(), nas.id());
        assertTrue(nas.name().startsWith("nas-"));
        assertTrue(nas.mountPath().startsWith("/mnt/it-"));
        assertTrue(nas.enabled());
        assertNotNull(nas.createdAt());
        assertNotNull(nas.updatedAt());

        // customer_space
        CustomerSpaceRow cs = customerSpaceRepository.findById(c.customerSpaceId()).orElseThrow();
        assertEquals(c.customerSpaceId(), cs.id());
        assertTrue(cs.code().startsWith("cs-"));
        assertNotNull(cs.name());
        assertTrue(cs.enabled());

        // directory
        DirectoryRow dir = directoryRepository.findById(c.directoryId()).orElseThrow();
        assertEquals(c.directoryId(), dir.id());
        assertEquals(c.nasId(), dir.nasId());
        assertEquals(c.customerSpaceId(), dir.customerSpaceId());
        assertTrue(dir.code().startsWith("dir-"));
        assertTrue(dir.extensions().contains("\"zip\""));
        assertTrue(dir.extensions().contains("\"xml\""));
        assertEquals(60L, dir.scanIntervalSec());
        assertEquals(4, dir.maxConcurrency());
        assertTrue(dir.enabled());
        assertNull(dir.lastScanAt());

        // file
        FileRow file = fileRepository.findById(c.fileId()).orElseThrow();
        assertEquals(c.fileId(), file.id());
        assertEquals(c.nasId(), file.nasId());
        assertEquals(c.directoryId(), file.directoryId());
        assertTrue(file.relativePath().startsWith("x/it-"));

        // file_version
        FileVersionRow fv = fileVersionRepository.findById(c.fileVersionId()).orElseThrow();
        assertEquals(c.fileVersionId(), fv.id());
        assertEquals(c.fileId(), fv.fileId());
        assertEquals(1, fv.versionNo());
        assertEquals(123L, fv.sizeBytes());
        assertTrue(fv.fingerprint().startsWith("fp-"));
        assertNotNull(fv.mtime());
        assertNotNull(fv.firstSeenAt()); // DB DEFAULT now()
        assertNotNull(fv.stableAt());    // fixture 明确写入
        assertNotNull(fv.createdAt());
        assertNotNull(fv.updatedAt());

        // transfer_task
        TransferTaskRow task = transferTaskRepository.findById(c.taskId()).orElseThrow();
        assertEquals(c.taskId(), task.id());
        assertEquals(c.fileVersionId(), task.fileVersionId());
        assertEquals(c.fileId(), task.fileId());
        assertEquals(1, task.versionNo());
        assertEquals(c.directoryId(), task.directoryId());
        assertEquals(c.customerSpaceId(), task.customerSpaceId());
        assertTrue(task.filePath().startsWith("dir/x-"));
        assertEquals(123L, task.fileSizeBytes());
        assertNotNull(task.fileMtime());
        assertEquals(IT_BUCKET, task.s3Bucket());
        assertTrue(task.s3ObjectKey().startsWith("it-key-"));
        assertNull(task.s3UploadedAt());
        assertTrue(task.gatewayRoute().startsWith("route-"));
        assertEquals(IT_CONFIG_VERSION, task.configVersion());
        assertEquals(TaskStatus.READY, task.status());
        assertEquals(0, task.priority());
        assertEquals(0, task.retryCount());
        assertNull(task.nextRetryAt());
        assertNull(task.workerId());
        assertNull(task.claimedAt());
        assertNull(task.leaseUntil());
        assertNull(task.lastAttemptFinishedAt());
        assertNull(task.cancelReason());
        assertNull(task.supersededByVersionNo());
        assertNull(task.cancelledAt());
        assertNull(task.startedAt());
        assertNull(task.completedAt());
        assertNotNull(task.createdAt());
        assertNotNull(task.updatedAt());

        // transfer_attempt：S3_UPLOAD/SUCCESS + GATEWAY_DELIVER/UNKNOWN（A4：结果未知可落库）
        long attemptUploadId = transferAttemptRepository.insert(new TransferAttemptRow(
                null, c.taskId(), AttemptType.S3_UPLOAD, 1, null, null,
                true, AttemptOutcome.SUCCESS, 200, null, null, null, null, null, null));
        TransferAttemptRow uploadAttempt = transferAttemptRepository.findById(attemptUploadId).orElseThrow();
        assertEquals(c.taskId(), uploadAttempt.transferTaskId());
        assertEquals(AttemptType.S3_UPLOAD, uploadAttempt.attemptType());
        assertEquals(1, uploadAttempt.attemptNo());
        assertTrue(uploadAttempt.success());
        assertEquals(AttemptOutcome.SUCCESS, uploadAttempt.outcome());
        assertNotNull(uploadAttempt.createdAt());

        long attemptDeliverId = transferAttemptRepository.insert(new TransferAttemptRow(
                null, c.taskId(), AttemptType.GATEWAY_DELIVER, 1, null, null,
                false, AttemptOutcome.UNKNOWN, null, null, null, null, null, null, null));
        TransferAttemptRow deliverAttempt = transferAttemptRepository.findById(attemptDeliverId).orElseThrow();
        assertEquals(AttemptType.GATEWAY_DELIVER, deliverAttempt.attemptType());
        assertFalse(deliverAttempt.success());
        assertEquals(AttemptOutcome.UNKNOWN, deliverAttempt.outcome());
        assertNull(deliverAttempt.finishedAt());

        // transfer_event
        long eventId = transferEventRepository.insert(new TransferEventRow(
                null, c.taskId(), "READY", "DELIVERED", null, "system", null, null));
        TransferEventRow event = transferEventRepository.findById(eventId).orElseThrow();
        assertEquals(c.taskId(), event.transferTaskId());
        assertEquals("READY", event.fromStatus());
        assertEquals("DELIVERED", event.toStatus());
        assertNull(event.reason());
        assertEquals("system", event.operator());
        assertNull(event.detail());
        assertNotNull(event.createdAt());
    }

    @Test
    void duplicateFileFingerprintRejected() {
        Chain c = chain();
        FileVersionRow original = fileVersionRepository.findById(c.fileVersionId()).orElseThrow();
        // 同 file：versionNo=2（不冲突）但 fingerprint 相同 → UNIQUE(file_id, fingerprint)
        FileVersionRow dup = new FileVersionRow(
                null, c.fileId(), 2, 456L, OffsetDateTime.now(),
                original.fingerprint(), null, null, null, null);
        assertThrows(DataIntegrityViolationException.class, () -> fileVersionRepository.insert(dup));
    }

    @Test
    void duplicateFileVersionNoRejected() {
        Chain c = chain();
        // 同 file：versionNo=1 重复但 fingerprint 不同 → UNIQUE(file_id, version_no)
        FileVersionRow dup = new FileVersionRow(
                null, c.fileId(), 1, 456L, OffsetDateTime.now(),
                "fp-other-" + System.nanoTime(), null, null, null, null);
        assertThrows(DataIntegrityViolationException.class, () -> fileVersionRepository.insert(dup));
    }

    @Test
    void duplicateTransferTaskForVersionRejected() {
        Chain c = chain();
        // 同一 file_version_id 的第二条任务（s3ObjectKey 不同）→ UNIQUE(file_version_id)
        TransferTaskRow dup = new TransferTaskRow(
                null, c.fileVersionId(), c.fileId(), 1, c.directoryId(), c.customerSpaceId(),
                "dir/y-" + System.nanoTime() + ".csv", 456L, OffsetDateTime.now(),
                IT_BUCKET, "other-key-" + System.nanoTime(), null,
                "route-other-" + System.nanoTime(), IT_CONFIG_VERSION, TaskStatus.READY, 0, 0,
                null, null, null, null, null, null, null, null, null, null, null, null);
        assertThrows(DataIntegrityViolationException.class, () -> transferTaskRepository.insert(dup));
    }

    @Test
    void duplicateTransferAttemptRejected() {
        Chain c = chain();
        TransferAttemptRow first = new TransferAttemptRow(
                null, c.taskId(), AttemptType.S3_UPLOAD, 1, null, null,
                true, AttemptOutcome.SUCCESS, null, null, null, null, null, null, null);
        transferAttemptRepository.insert(first);
        // 同一 (task, S3_UPLOAD, attemptNo=1) 再插 → UNIQUE(transfer_task_id, attempt_type, attempt_no)
        TransferAttemptRow second = new TransferAttemptRow(
                null, c.taskId(), AttemptType.S3_UPLOAD, 1, null, null,
                true, AttemptOutcome.SUCCESS, null, null, null, null, null, null, null);
        assertThrows(DataIntegrityViolationException.class, () -> transferAttemptRepository.insert(second));
    }

    @Test
    void duplicateDirectoryPathRejected() {
        Chain c = chain();
        DirectoryRow original = directoryRepository.findById(c.directoryId()).orElseThrow();
        // 同 nas 同 path、不同 code → UNIQUE(nas_id, path)
        DirectoryRow dup = new DirectoryRow(
                null, original.nasId(), original.customerSpaceId(),
                "dir-other-" + System.nanoTime(), original.path(), original.extensions(),
                60L, 4, true, null, null, null);
        assertThrows(DataIntegrityViolationException.class, () -> directoryRepository.insert(dup));
    }

    @Test
    void missingParentFkViolationRejected() {
        Chain c = chain();
        // 引用不存在的 file_version（FK fk_transfer_task_file_version）
        TransferTaskRow orphan = new TransferTaskRow(
                null, 9_999_999L, c.fileId(), 1, c.directoryId(), c.customerSpaceId(),
                "dir/orphan-" + System.nanoTime() + ".csv", 1L, OffsetDateTime.now(),
                IT_BUCKET, "orphan-key-" + System.nanoTime(), null,
                "route-orphan-" + System.nanoTime(), IT_CONFIG_VERSION, TaskStatus.READY, 0, 0,
                null, null, null, null, null, null, null, null, null, null, null, null);
        assertThrows(DataIntegrityViolationException.class, () -> transferTaskRepository.insert(orphan));
    }

    @Test
    void forbiddenStatusRejected() {
        Chain c = chain();
        // 绕过枚举直插非法 9 态外状态 'FAILED' → CHECK ck_transfer_task_status 拒绝
        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "INSERT INTO transfer_task (file_version_id,file_id,version_no,directory_id,"
                        + "customer_space_id,file_path,file_size_bytes,file_mtime,s3_bucket,"
                        + "s3_object_key,gateway_route,config_version,status,priority,retry_count) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,0,0)",
                c.fileVersionId(), c.fileId(), 1, c.directoryId(), c.customerSpaceId(),
                "dir/forbidden-" + System.nanoTime() + ".csv", 1L, OffsetDateTime.now(),
                IT_BUCKET, "forbidden-key-" + System.nanoTime(), "route-forbidden",
                IT_CONFIG_VERSION, "FAILED"));
    }
}
