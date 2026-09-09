package com.example.transfer.scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

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
import com.example.transfer.repository.TransferTaskRepository;
import com.example.transfer.repository.TransferTaskRow;

/**
 * DirectoryScanner 集成测试：
 *
 * <p>测试场景：
 * <ul>
 *   <li>1. 新文件扫描：首次发现落库并进入 STABILITY_CHECK</li>
 *   <li>2. 重复扫描（连续两次相同）：达到稳定进入 READY，且再次扫描不重复创建任务</li>
 *   <li>3. 多文件与后缀过滤：仅符合白名单的文件被处理，其他忽略</li>
 *   <li>4. 文件消失：在稳定前从临时目录删除文件，重新扫描后 task 转为 CANCELLED</li>
 *   <li>5. 稳定后文件被修改：再次扫描生成 version_no=2 的新版本与新 task</li>
 *   <li>6. 目录不存在/NAS 故障：返回 failure，不崩溃</li>
 * </ul>
 */
@SpringBootTest
class DirectoryScannerTest {

    @Autowired
    private DirectoryScanner scanner;

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
    private TransferTaskRepository taskRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @TempDir
    Path tempDir;

    private static final AtomicInteger SEQ = new AtomicInteger();

    private long nasId;
    private long customerSpaceId;
    private long directoryId;
    private String directoryCode;
    private String customerSpaceCode;

    @BeforeEach
    void setUp() {
        int id = SEQ.incrementAndGet();
        directoryCode = "dir-" + id + "-" + System.currentTimeMillis();
        customerSpaceCode = "space-" + id;

        nasId = nasRepository.insert(new NasRow(null, "nas-" + id, tempDir.toString(), true, null, null));
        customerSpaceId = customerSpaceRepository.insert(
                new CustomerSpaceRow(null, customerSpaceCode, "Customer " + id, true, null, null));
        directoryId = directoryRepository.insert(new DirectoryRow(
                null, nasId, customerSpaceId, directoryCode, "mock-path", "[\".zip\", \".csv\"]",
                60L, 2, true, null, null, null));
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM transfer_event WHERE transfer_task_id IN (SELECT id FROM transfer_task WHERE directory_id = ?)", directoryId);
        jdbcTemplate.update("DELETE FROM transfer_attempt WHERE transfer_task_id IN (SELECT id FROM transfer_task WHERE directory_id = ?)", directoryId);
        jdbcTemplate.update("DELETE FROM transfer_task WHERE directory_id = ?", directoryId);
        jdbcTemplate.update("DELETE FROM file_version WHERE file_id IN (SELECT id FROM file WHERE directory_id = ?)", directoryId);
        jdbcTemplate.update("DELETE FROM file WHERE directory_id = ?", directoryId);
        jdbcTemplate.update("DELETE FROM directory WHERE id = ?", directoryId);
        jdbcTemplate.update("DELETE FROM customer_space WHERE id = ?", customerSpaceId);
        jdbcTemplate.update("DELETE FROM nas WHERE id = ?", nasId);
    }

    private DirectoryScanTarget scanTarget() {
        return new DirectoryScanTarget(
                directoryId, nasId, customerSpaceId, directoryCode, customerSpaceCode,
                tempDir, List.of(".zip", ".csv"), 60L, true);
    }

    @Test
    void test1_newFileDiscovered() throws IOException {
        Path file = tempDir.resolve("order.csv");
        Files.writeString(file, "order_id,amount\n1,100\n");

        ScanResult result = scanner.scan(scanTarget());
        assertTrue(result.success());
        assertEquals(1, result.discoveredCount());

        Optional<FileRow> fileRowOpt = fileRepository.findByPath(nasId, directoryId, "order.csv");
        assertTrue(fileRowOpt.isPresent());

        Optional<FileVersionRow> versionOpt = fileVersionRepository.findLatestByFileId(fileRowOpt.get().id());
        assertTrue(versionOpt.isPresent());
        assertEquals(1, versionOpt.get().versionNo());

        Optional<TransferTaskRow> taskOpt = taskRepository.findByFileVersionId(versionOpt.get().id());
        assertTrue(taskOpt.isPresent());
        assertEquals(TaskStatus.STABILITY_CHECK, taskOpt.get().status());
    }

    @Test
    void test2_twoIdenticalObservationsBecomeReadyAndIdempotent() throws IOException {
        Path file = tempDir.resolve("invoice.csv");
        Files.writeString(file, "inv_1,50\n");
        // 固定修改时间，确保两次扫描完全相同
        Files.setLastModifiedTime(file, FileTime.from(Instant.parse("2026-09-09T10:00:00Z")));

        DirectoryScanTarget target = scanTarget();

        // 第一次扫描：发现文件，进入 STABILITY_CHECK
        ScanResult r1 = scanner.scan(target);
        assertEquals(1, r1.discoveredCount());
        assertEquals(0, r1.stableCount());

        // 第二次扫描：完全相同，转入 READY
        ScanResult r2 = scanner.scan(target);
        assertEquals(0, r2.discoveredCount());
        assertEquals(1, r2.stableCount());

        FileRow fileRow = fileRepository.findByPath(nasId, directoryId, "invoice.csv").orElseThrow();
        FileVersionRow version = fileVersionRepository.findLatestByFileId(fileRow.id()).orElseThrow();
        TransferTaskRow task = taskRepository.findByFileVersionId(version.id()).orElseThrow();
        assertEquals(TaskStatus.READY, task.status());
        assertNotNull(version.stableAt());

        // 第三次扫描：幂等跳过，不重复创建任务，unchanged=1
        ScanResult r3 = scanner.scan(target);
        assertEquals(0, r3.discoveredCount());
        assertEquals(0, r3.stableCount());
        assertEquals(1, r3.unchangedCount());
    }

    @Test
    void test3_multiFilesAndExtensionFilter() throws IOException {
        Files.writeString(tempDir.resolve("valid1.zip"), "zip-content");
        Files.writeString(tempDir.resolve("valid2.csv"), "csv-content");
        Files.writeString(tempDir.resolve("ignored.txt"), "txt-content"); // 不在白名单

        ScanResult result = scanner.scan(scanTarget());
        assertTrue(result.success());
        assertEquals(2, result.discoveredCount());

        assertTrue(fileRepository.findByPath(nasId, directoryId, "valid1.zip").isPresent());
        assertTrue(fileRepository.findByPath(nasId, directoryId, "valid2.csv").isPresent());
        assertFalse(fileRepository.findByPath(nasId, directoryId, "ignored.txt").isPresent());
    }

    @Test
    void test4_fileVanishedBeforeStable() throws IOException {
        Path file = tempDir.resolve("temporary.csv");
        Files.writeString(file, "temp");

        DirectoryScanTarget target = scanTarget();
        scanner.scan(target); // 第一次扫描：STABILITY_CHECK

        // 文件在稳定前被删除
        Files.delete(file);

        ScanResult r2 = scanner.scan(target); // 第二次扫描
        assertEquals(1, r2.vanishedCount());

        FileRow fileRow = fileRepository.findByPath(nasId, directoryId, "temporary.csv").orElseThrow();
        FileVersionRow version = fileVersionRepository.findLatestByFileId(fileRow.id()).orElseThrow();
        TransferTaskRow task = taskRepository.findByFileVersionId(version.id()).orElseThrow();
        assertEquals(TaskStatus.CANCELLED, task.status());
        assertEquals("FILE_MISSING", task.cancelReason());
    }

    @Test
    void test5_modifiedAfterReadyCreatesNewVersion() throws IOException {
        Path file = tempDir.resolve("data.csv");
        Files.writeString(file, "v1");
        Files.setLastModifiedTime(file, FileTime.from(Instant.parse("2026-09-09T10:00:00Z")));

        DirectoryScanTarget target = scanTarget();
        scanner.scan(target); // 发现
        scanner.scan(target); // 稳定 (READY)

        // 覆盖修改文件内容与 mtime
        Files.writeString(file, "v2-modified-content");
        Files.setLastModifiedTime(file, FileTime.from(Instant.parse("2026-09-09T11:00:00Z")));

        ScanResult r3 = scanner.scan(target);
        assertEquals(1, r3.newVersionCount());

        FileRow fileRow = fileRepository.findByPath(nasId, directoryId, "data.csv").orElseThrow();
        FileVersionRow v2 = fileVersionRepository.findLatestByFileId(fileRow.id()).orElseThrow();
        assertEquals(2, v2.versionNo());

        TransferTaskRow task2 = taskRepository.findByFileVersionId(v2.id()).orElseThrow();
        assertEquals(TaskStatus.STABILITY_CHECK, task2.status());
    }

    @Test
    void test6_directoryNotExistReturnsFailure() {
        Path invalidDir = tempDir.resolve("non-existent-sub-dir");
        DirectoryScanTarget target = new DirectoryScanTarget(
                directoryId, nasId, customerSpaceId, directoryCode, customerSpaceCode,
                invalidDir, List.of(".csv"), 60L, true);

        ScanResult result = scanner.scan(target);
        assertFalse(result.success());
        assertNotNull(result.errorMessage());
    }
}
