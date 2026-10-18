package com.arte.ainew.tool.management;

import com.arte.ainew.persistence.codec.JacksonExecutionRecordCodec;
import com.arte.ainew.pojo.tool.ToolDiscoveryReceipt;
import com.arte.ainew.pojo.tool.ToolRelease;
import com.arte.core.enums.platform.PrincipalKindEnum;
import com.arte.core.pojo.platform.Scope;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

/**
 * 发现审计的部署结构与白名单版本必须与当前管理持久化一致。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 12:52 ✾
 */
public class ToolDiscoveryContractTest {
    @Test
    public void baselineAndIncrementalDdlAreIdentical() throws Exception {
        var baseline = Files.readString(Path.of("scripts/arte-ai-new-ddl-mysql.sql"));
        var incremental = Files.readString(Path.of("scripts/arte-ai-tool-management-migration.sql"));
        assertEquals(incremental.substring(incremental.indexOf("create table ")).trim(),
                baseline.substring(baseline.indexOf("create table arte_ai_tool_discovery_command\n")).trim());
    }

    @Test
    public void discoveryReceiptHasClosedRootAndStrictIntegerVersion() {
        var codec = new JacksonExecutionRecordCodec();
        var receipt = new ToolDiscoveryReceipt("a".repeat(64), "subject", PrincipalKindEnum.USER,
                new Scope("tenant", "workspace"), "command-key", "review", ToolDiscoveryReceipt.Code.APPLIED,
                "b".repeat(64), 1, 2, 1, Instant.parse("2026-10-18T01:00:00Z"));
        var json = codec.encode(receipt);
        assertEquals(receipt, codec.decode(json, ToolDiscoveryReceipt.class));
        for (var version : new String[]{"99", "1.2", "\"1\""})
            assertThrows(RuntimeException.class, () -> codec.decode(json.replace("\"schemaVersion\":1", "\"schemaVersion\":" + version), ToolDiscoveryReceipt.class));
        assertThrows(RuntimeException.class, () -> codec.decode(json, ToolRelease.class));
    }
}
