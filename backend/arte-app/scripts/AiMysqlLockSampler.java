import java.nio.file.*;
import java.sql.*;
import java.util.*;

import tools.jackson.databind.json.JsonMapper;

/**
 * 只读 MySQL 锁观测；不启用 Performance Schema、不修改数据库设置、不读取会话凭据。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/14 09:41 ✾
 */
public class AiMysqlLockSampler {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static Map<String, Long> counters(Connection connection) throws SQLException {
        var values = new TreeMap<String, Long>();
        try (var query = connection.createStatement(); var rows = query.executeQuery(
                "SHOW GLOBAL STATUS WHERE Variable_name IN ('Innodb_row_lock_time','Innodb_row_lock_waits','Innodb_row_lock_current_waits')")) {
            while (rows.next()) values.put(rows.getString(1), Long.parseLong(rows.getString(2)));
        }
        return values;
    }

    private static void write(java.io.Writer writer, Map<String, ?> values) throws java.io.IOException {
        writer.write(JSON.writeValueAsString(values) + "\n");
        writer.flush();
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) throw new IllegalArgumentException("Output directory required");
        var folder = Path.of(args[0]);
        Files.createDirectories(folder);
        var properties = new Properties();
        for (var profile : List.of("backend/profile/common.properties", "backend/profile/app.properties"))
            try (var input = Files.newBufferedReader(Path.of(profile))) {
                properties.load(input);
            }
        long deadline = System.nanoTime() + (args.length > 1 ? Long.parseLong(args[1]) : 600) * 1_000_000_000L;
        try (var connection = DriverManager.getConnection(properties.getProperty("spring.datasource.druid.app.url"),
                properties.getProperty("spring.datasource.druid.app.username"), properties.getProperty("spring.datasource.druid.app.password"));
             var writer = Files.newBufferedWriter(folder.resolve("mysql-lock-samples.jsonl"))) {
            connection.setReadOnly(true);
            boolean performanceSchema;
            try (var query = connection.createStatement(); var rows = query.executeQuery("SELECT @@performance_schema")) {
                rows.next();
                performanceSchema = rows.getBoolean(1);
            }
            write(writer, Map.of("kind", "ready", "performanceSchemaEnabled", performanceSchema, "atEpochMs", System.currentTimeMillis()));
            System.out.println("MySQL lock sampler ready");
            for (String group : List.of("single", "concurrent")) {
                while (!Files.exists(folder.resolve(group + "-start.marker")) && System.nanoTime() < deadline)
                    Thread.sleep(25);
                if (System.nanoTime() >= deadline) break;
                var before = counters(connection);
                write(writer, Map.of("kind", "begin", "group", group, "atEpochMs", System.currentTimeMillis(), "counters", before));
                Files.writeString(folder.resolve(group + "-lock-start.ready"), "ready");
                int samples = 0, waitingRows = 0;
                String access = "available";
                while (!Files.exists(folder.resolve(group + "-end.marker")) && System.nanoTime() < deadline) {
                    try (var query = connection.createStatement()) {
                        query.setQueryTimeout(5);
                        var waiting = new ArrayList<Long>();
                        try (var rows = query.executeQuery("SELECT trx_mysql_thread_id FROM information_schema.innodb_trx WHERE trx_state='LOCK WAIT'")) {
                            while (rows.next()) waiting.add(rows.getLong(1));
                        }
                        samples++;
                        waitingRows += waiting.size();
                        if (!waiting.isEmpty())
                            write(writer, Map.of("kind", "wait", "group", group, "atEpochMs", System.currentTimeMillis(), "mysqlConnectionIds", waiting));
                    } catch (SQLException denied) {
                        access = denied.getSQLState();
                    }
                    Thread.sleep(100);
                }
                var after = counters(connection);
                var difference = new TreeMap<String, Long>();
                for (String name : List.of("Innodb_row_lock_time", "Innodb_row_lock_waits"))
                    difference.put(name, after.get(name) - before.get(name));
                write(writer, Map.of("kind", "end", "group", group, "atEpochMs", System.currentTimeMillis(), "counters", after,
                        "globalDelta", difference, "samples", samples, "waitingSamples", waitingRows, "transactionAccess", access));
                Files.writeString(folder.resolve(group + "-lock-end.ready"), "ready");
            }
        } catch (SQLException failure) {
            System.err.println("MySQL observation unavailable, sqlState=" + failure.getSQLState() + ", vendorCode=" + failure.getErrorCode());
            System.exit(2);
        }
    }
}
