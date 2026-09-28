package com.arte.ai.service.tool;

import com.arte.ai.mapper.tool.ToolTaskMapper;
import com.arte.ai.pojo.tool.ToolReference;
import com.arte.ai.pojo.tool.ToolTaskHandle;
import com.arte.ai.pojo.tool.ToolTaskPage;
import com.arte.ai.pojo.tool.po.ToolTaskPo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;

/**
 * 工具任务只读查询服务；仅暴露当前用户拥有的任务，不返回恢复令牌或凭据快照。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/28 ✾
 */
@Service
@RequiredArgsConstructor
public class ToolTaskQueryService {

    private static final int MAX_PAGE_SIZE = 200;
    private static final String META_NAMESPACE = "toolNamespace";
    private static final String META_NAME = "toolName";
    private static final String META_VERSION = "toolVersion";

    private final ToolTaskMapper taskMapper;

    public ToolTaskPage listOwned(String ownerId, ToolTaskHandle.Status status, int current, int size) {
        int safeCurrent = Math.max(1, current);
        int safeSize = Math.max(1, Math.min(MAX_PAGE_SIZE, size));
        long offset = (long) (safeCurrent - 1) * safeSize;
        String persistedStatus = status == null ? null : status.name().toLowerCase();
        var records = taskMapper.selectOwned(ownerId, persistedStatus, offset, safeSize)
                .stream().map(this::toHandle).toList();
        return new ToolTaskPage(records, taskMapper.countOwned(ownerId, persistedStatus), safeCurrent, safeSize);
    }

    private ToolTaskHandle toHandle(ToolTaskPo task) {
        Map<String, Object> metadata = task.getMetadata() == null ? Map.of() : task.getMetadata();
        ToolReference reference = new ToolReference(requiredMetadata(metadata, META_NAMESPACE),
                requiredMetadata(metadata, META_NAME), requiredMetadata(metadata, META_VERSION));
        return new ToolTaskHandle(task.getTaskId(), task.getCallId(), reference,
                ToolTaskHandle.Status.valueOf(task.getStatus().toUpperCase()),
                task.getProgress() == null ? null : task.getProgress().doubleValue(), task.getProgressMessage(),
                null, toInstant(task.getCreateTime()), toInstant(task.getUpdateTime()),
                task.getRowVersion() == null ? 0 : task.getRowVersion(), metadata);
    }

    private String requiredMetadata(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalStateException("tool task metadata is missing " + key);
        }
        return String.valueOf(value);
    }

    private Instant toInstant(LocalDateTime value) {
        if (value == null) {
            throw new IllegalStateException("tool task timestamp must not be null");
        }
        return value.atZone(ZoneId.systemDefault()).toInstant();
    }
}
