package com.example.transfer.scanner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.example.transfer.file.FileFingerprint;
import com.example.transfer.file.FileMetadata;
import com.example.transfer.file.FileObservation;
import com.example.transfer.file.FileStabilityChecker;
import com.example.transfer.file.StabilityAssessment;
import com.example.transfer.repository.DirectoryRepository;
import com.example.transfer.repository.FileRepository;
import com.example.transfer.repository.FileRow;
import com.example.transfer.repository.FileVersionRepository;
import com.example.transfer.repository.FileVersionRow;
import com.example.transfer.repository.TaskStatus;
import com.example.transfer.repository.TransferTaskRepository;
import com.example.transfer.repository.TransferTaskRow;
import com.example.transfer.storage.S3KeyGenerator;
import com.example.transfer.task.TaskTransitionService;
import com.example.transfer.task.TransitionRequest;

/**
 * NAS 目录扫描与稳定状态驱动器：
 *
 * <p><strong>核心原则（提示词 §三、§十二；01-functional.md FR-01/FR-02）</strong>：
 * <ul>
 *   <li>只负责发现物理事实并推进至 {@code READY}，<strong>绝对不执行耗时上传</strong>，不阻塞 Worker；</li>
 *   <li>重复扫描同一文件幂等跳过，<strong>绝不重复创建任务</strong>；</li>
 *   <li>仅当连续两次扫描 {@code path + size + mtime} 完全一致，才将任务由 {@code STABILITY_CHECK}
 *       迁移为 {@code READY}（T4）；</li>
 *   <li>扫描异常（路径不可达/IO 错误）不扩散，记录日志并返回失败统计，不破坏系统运行。</li>
 * </ul>
 */
@Component
public class DirectoryScanner {

    private static final Logger log = LoggerFactory.getLogger(DirectoryScanner.class);

    private final FileRepository fileRepository;
    private final FileVersionRepository fileVersionRepository;
    private final TransferTaskRepository taskRepository;
    private final DirectoryRepository directoryRepository;
    private final FileStabilityChecker stabilityChecker;
    private final TaskTransitionService transitionService;
    private final JdbcTemplate jdbcTemplate;

    public DirectoryScanner(
            FileRepository fileRepository,
            FileVersionRepository fileVersionRepository,
            TransferTaskRepository taskRepository,
            DirectoryRepository directoryRepository,
            FileStabilityChecker stabilityChecker,
            TaskTransitionService transitionService,
            JdbcTemplate jdbcTemplate) {
        this.fileRepository = fileRepository;
        this.fileVersionRepository = fileVersionRepository;
        this.taskRepository = taskRepository;
        this.directoryRepository = directoryRepository;
        this.stabilityChecker = stabilityChecker;
        this.transitionService = transitionService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 对指定目录执行一次全量文件扫描与状态流转。
     */
    public ScanResult scan(DirectoryScanTarget target) {
        if (!target.enabled()) {
            log.info("目录 [{}] 已禁用，跳过本次扫描", target.directoryCode());
            return ScanResult.success(0, 0, 0, 0, 0, 0);
        }

        Path root = target.rootPath();
        if (!Files.exists(root) || !Files.isDirectory(root)) {
            String err = "NAS 挂载目录不存在或不是有效目录: " + root;
            log.error("扫描失败: directoryCode={}, err={}", target.directoryCode(), err);
            return ScanResult.failure(err);
        }

        List<FileMetadata> physicalFiles;
        try {
            physicalFiles = collectFiles(target);
        } catch (IOException e) {
            String err = "遍历目录 IO 异常: " + e.getMessage();
            log.error("扫描目录异常: directoryCode={}, path={}", target.directoryCode(), root, e);
            return ScanResult.failure(err);
        }

        int discovered = 0;
        int stable = 0;
        int modified = 0;
        int unchanged = 0;
        int newVersion = 0;
        int vanished = 0;

        Set<String> seenPaths = new HashSet<>();

        for (FileMetadata meta : physicalFiles) {
            seenPaths.add(meta.relativePath());
            try {
                ScanAction action = processDiscoveredFile(target, meta);
                switch (action) {
                    case DISCOVERED -> discovered++;
                    case STABLE -> stable++;
                    case MODIFIED -> modified++;
                    case UNCHANGED -> unchanged++;
                    case NEW_VERSION -> newVersion++;
                }
            } catch (Exception e) {
                log.error("处理文件异常: directoryCode={}, relativePath={}",
                        target.directoryCode(), meta.relativePath(), e);
            }
        }

        // 检查稳定前消失的文件（原处于 STABILITY_CHECK 但本轮未扫到）
        vanished = handleVanishedFiles(target, seenPaths);

        // 记录最后一次成功扫描时刻
        updateLastScanAt(target.directoryId());

        return ScanResult.success(discovered, stable, modified, vanished, unchanged, newVersion);
    }

    private List<FileMetadata> collectFiles(DirectoryScanTarget target) throws IOException {
        Path root = target.rootPath();
        List<FileMetadata> result = new ArrayList<>();

        try (Stream<Path> stream = Files.walk(root)) {
            stream.forEach(path -> {
                // 不跟随符号链接，只处理常规文件
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    String fileName = path.getFileName().toString();
                    if (target.matchesExtension(fileName)) {
                        Path rel = root.relativize(path);
                        String relPathStr = rel.toString().replace('\\', '/');
                        try {
                            BasicFileAttributes attrs = Files.readAttributes(path,
                                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                            long size = attrs.size();
                            OffsetDateTime mtime = attrs.lastModifiedTime().toInstant()
                                    .atOffset(java.time.ZoneOffset.UTC);
                            result.add(FileMetadata.of(relPathStr, size, mtime));
                        } catch (IOException e) {
                            log.warn("读取文件属性失败，跳过: {}", path, e);
                        }
                    }
                }
            });
        }
        return result;
    }

    private enum ScanAction {
        DISCOVERED,
        STABLE,
        MODIFIED,
        UNCHANGED,
        NEW_VERSION
    }

    private ScanAction processDiscoveredFile(DirectoryScanTarget target, FileMetadata meta) {
        long nasId = target.nasId();
        long dirId = target.directoryId();
        String relPath = meta.relativePath();

        FileRow file = fileRepository.findOrCreate(nasId, dirId, relPath);
        Optional<FileVersionRow> latestVersionOpt = fileVersionRepository.findLatestByFileId(file.id());

        if (latestVersionOpt.isEmpty()) {
            // 场景 1：全新文件首次发现
            createNewTask(target, file, 1, meta);
            return ScanAction.DISCOVERED;
        }

        FileVersionRow latestVersion = latestVersionOpt.get();
        Optional<TransferTaskRow> taskOpt = taskRepository.findByFileVersionId(latestVersion.id());
        if (taskOpt.isEmpty()) {
            // 保护性回退：有版本无任务，补建任务
            createTaskForVersion(target, file, latestVersion, meta);
            return ScanAction.DISCOVERED;
        }

        TransferTaskRow task = taskOpt.get();

        if (task.status() == TaskStatus.STABILITY_CHECK) {
            // 正在进行稳定性观察对比
            FileObservation prevObs = new FileObservation(
                    relPath,
                    latestVersion.sizeBytes(),
                    latestVersion.mtime(),
                    latestVersion.fingerprint(),
                    latestVersion.firstSeenAt(),
                    false);

            StabilityAssessment assessment = stabilityChecker.assess(prevObs, meta);
            if (assessment.isStable()) {
                // 场景 2：连续两次观察完全相同 → 稳定！T4 进入 READY
                transitionService.transition(new TransitionRequest(
                        task.id(),
                        "T4",
                        TaskStatus.STABILITY_CHECK,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        OffsetDateTime.now(),
                        null,
                        null,
                        null));
                return ScanAction.STABLE;
            } else if (assessment.status() == com.example.transfer.file.StabilityStatus.MODIFIED) {
                // 场景 3/4：大小或 mtime 发生变化，仍在写入或被覆盖
                String newFp = assessment.currentFingerprint();
                fileVersionRepository.updateMetadata(latestVersion.id(), meta.sizeBytes(), meta.mtime(), newFp);
                // 触发 T3 自迁移，记录 fingerprint-changed 事件，保持 STABILITY_CHECK
                transitionService.transition(new TransitionRequest(
                        task.id(),
                        "T3",
                        TaskStatus.STABILITY_CHECK,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "fingerprint-changed",
                        null));
                return ScanAction.MODIFIED;
            } else {
                return ScanAction.UNCHANGED;
            }
        }

        // 任务已处于 READY 或后续流程（UPLOADING / S3_UPLOADED / DELIVERED / WAITING_RETRY 等）
        String currentFp = FileFingerprint.compute(meta);
        if (currentFp.equals(latestVersion.fingerprint())) {
            // 指纹相同，文件无变动，幂等跳过，绝不重复创建任务
            return ScanAction.UNCHANGED;
        }

        // 指纹不同：文件在上一版本处理后发生新变更，产生新版本（latest wins）
        int nextVersionNo = latestVersion.versionNo() + 1;
        createNewTask(target, file, nextVersionNo, meta);
        return ScanAction.NEW_VERSION;
    }

    private void createNewTask(DirectoryScanTarget target, FileRow file, int versionNo, FileMetadata meta) {
        String fp = FileFingerprint.compute(meta);
        long versionId = fileVersionRepository.insert(new FileVersionRow(
                null,
                file.id(),
                versionNo,
                meta.sizeBytes(),
                meta.mtime(),
                fp,
                OffsetDateTime.now(),
                null,
                null,
                null));

        String s3Key = S3KeyGenerator.generate(target.customerSpaceCode(), meta.relativePath());
        long taskId = taskRepository.insert(new TransferTaskRow(
                null,
                versionId,
                file.id(),
                versionNo,
                target.directoryId(),
                target.customerSpaceId(),
                meta.relativePath(),
                meta.sizeBytes(),
                meta.mtime(),
                "default-bucket",
                s3Key,
                null,
                target.customerSpaceCode(),
                "v1",
                TaskStatus.DISCOVERED,
                0,
                0,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null));

        // 首次发现后立刻走 T2 转入 STABILITY_CHECK 启动稳定观察
        transitionService.transition(new TransitionRequest(
                taskId,
                "T2",
                TaskStatus.DISCOVERED,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null));
    }

    private void createTaskForVersion(DirectoryScanTarget target, FileRow file, FileVersionRow version, FileMetadata meta) {
        String s3Key = S3KeyGenerator.generate(target.customerSpaceCode(), meta.relativePath());
        long taskId = taskRepository.insert(new TransferTaskRow(
                null,
                version.id(),
                file.id(),
                version.versionNo(),
                target.directoryId(),
                target.customerSpaceId(),
                meta.relativePath(),
                meta.sizeBytes(),
                meta.mtime(),
                "default-bucket",
                s3Key,
                null,
                target.customerSpaceCode(),
                "v1",
                TaskStatus.DISCOVERED,
                0,
                0,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null));

        transitionService.transition(new TransitionRequest(
                taskId,
                "T2",
                TaskStatus.DISCOVERED,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null));
    }

    private int handleVanishedFiles(DirectoryScanTarget target, Set<String> seenPaths) {
        // 查找属于该 directory 且状态处于 STABILITY_CHECK 的所有任务
        List<TransferTaskRow> checkingTasks = jdbcTemplate.query(
                "SELECT * FROM transfer_task WHERE directory_id = ? AND status = 'STABILITY_CHECK'",
                (rs, rowNum) -> taskRepository.findById(rs.getLong("id")).orElse(null),
                target.directoryId());

        int count = 0;
        for (TransferTaskRow task : checkingTasks) {
            if (task != null && !seenPaths.contains(task.filePath())) {
                // 文件在稳定前从 NAS 消失，触发 T5 取消
                transitionService.transition(TransitionRequest.cancel(
                        task.id(),
                        "T5",
                        TaskStatus.STABILITY_CHECK,
                        "FILE_MISSING",
                        null));
                count++;
            }
        }
        return count;
    }

    private void updateLastScanAt(long directoryId) {
        jdbcTemplate.update("UPDATE directory SET last_scan_at = now(), updated_at = now() WHERE id = ?",
                directoryId);
    }
}
