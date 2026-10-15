import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/**
 * Read-only directory SQL baseline. No DDL, fixture writes, message reads or credential output.
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/15 12:00 ✾
 */
public class ConversationDirectoryMysqlProbe {

    private static String setting(Properties file, String suffix) {
        return System.getenv().getOrDefault("SPRING_DATASOURCE_DRUID_APP_" + suffix.toUpperCase(Locale.ROOT),
                file.getProperty("spring.datasource.druid.app." + suffix, ""));
    }

    private static String json(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private static String statistics(List<Double> values) {
        var sorted = values.stream().sorted().toList();
        return String.format(Locale.ROOT, "{\"samples\":%d,\"p50Ms\":%.3f,\"p95Ms\":%.3f,\"maxMs\":%.3f}",
                sorted.size(), sorted.get((sorted.size() - 1) / 2), sorted.get((int) Math.ceil(sorted.size() * .95) - 1), sorted.getLast());
    }

    private static List<Double> measure(PreparedStatement query, int count) throws SQLException {
        var values = new ArrayList<Double>();
        for (int i = -5; i < count; i++) {
            long started = System.nanoTime();
            try (var rows = query.executeQuery()) {
                while (rows.next()) rows.getObject(1);
            }
            if (i >= 0) values.add((System.nanoTime() - started) / 1_000_000.0);
        }
        return values;
    }

    public static void main(String[] args) {
        try {
            var file = new Properties();
            try (var reader = Files.newBufferedReader(Path.of(args.length > 0 ? args[0] : "profile/app.properties"))) {
                file.load(reader);
            }
            int count = args.length > 1 ? Integer.parseInt(args[1]) : 30;
            if (count < 5 || count > 100) throw new IllegalArgumentException("sample count must be 5..100");
            String url = setting(file, "url");
            if (!url.startsWith("jdbc:mysql:")) throw new IllegalArgumentException("MySQL URL required");
            var credentials = new Properties();
            credentials.setProperty("user", setting(file, "username"));
            credentials.setProperty("password", setting(file, "password"));
            credentials.setProperty("connectTimeout", "8000");
            credentials.setProperty("socketTimeout", "8000");
            try (var db = DriverManager.getConnection(url, credentials)) {
                db.setReadOnly(true);
                db.setAutoCommit(false);
                boolean present;
                try (var query = db.prepareStatement("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='arte_ai_conversation_metadata'");
                     var rows = query.executeQuery()) {
                    rows.next();
                    present = rows.getInt(1) == 1;
                }
                if (!present) {
                    System.out.println("{\"readOnly\":true,\"metadataTablePresent\":false,\"measured\":false,\"reason\":\"migration_required\"}");
                    db.rollback();
                    return;
                }
                String owner = null;
                long ownerCount = 0, total = 0, sort = 0;
                String conversation = null;
                try (var query = db.prepareStatement("SELECT COUNT(*) FROM arte_ai_conversation_metadata"); var rows = query.executeQuery()) {
                    rows.next();
                    total = rows.getLong(1);
                }
                try (var query = db.prepareStatement("SELECT owner_key,COUNT(*) c FROM arte_ai_conversation_metadata WHERE active=1 GROUP BY owner_key ORDER BY c DESC LIMIT 1"); var rows = query.executeQuery()) {
                    if (rows.next()) {
                        owner = rows.getString(1);
                        ownerCount = rows.getLong(2);
                    }
                }
                if (owner == null) {
                    System.out.println("{\"readOnly\":true,\"metadataTablePresent\":true,\"metadataRows\":" + total + ",\"measured\":false,\"reason\":\"no_active_metadata\"}");
                    db.rollback();
                    return;
                }
                String columns = "id_key,owner_key,conversation_id,title,metadata_version,pinned,title_manually_edited,auto_title_resolved,preview_text,user_message_count,last_message_micros,sort_micros,created_micros,statistics_ready";
                String base = "SELECT " + columns + " FROM arte_ai_conversation_metadata WHERE owner_key=? AND pinned=0 AND active=1";
                String order = " ORDER BY sort_micros DESC,conversation_id DESC LIMIT 21";
                var plans = new ArrayList<String>();
                for (String sql : List.of(base + order, base + " AND (sort_micros < ? OR (sort_micros=? AND conversation_id < ?))" + order)) {
                    try (var query = db.prepareStatement("EXPLAIN " + sql)) {
                        query.setString(1, owner);
                        if (sql.contains("sort_micros <")) {
                            query.setLong(2, Long.MAX_VALUE);
                            query.setLong(3, Long.MAX_VALUE);
                            query.setString(4, "probe");
                        }
                        try (var rows = query.executeQuery()) {
                            while (rows.next())
                                plans.add("{\"type\":" + json(rows.getString("type")) + ",\"key\":" + json(Objects.toString(rows.getString("key"), ""))
                                        + ",\"estimatedRows\":" + rows.getLong("rows") + ",\"extra\":" + json(Objects.toString(rows.getString("Extra"), "")) + "}");
                        }
                    }
                }
                List<Double> first;
                try (var query = db.prepareStatement(base + order)) {
                    query.setString(1, owner);
                    first = measure(query, count);
                    try (var rows = query.executeQuery()) {
                        while (rows.next()) {
                            sort = rows.getLong("sort_micros");
                            conversation = rows.getString("conversation_id");
                        }
                    }
                }
                String continuation = "null";
                if (conversation != null)
                    try (var query = db.prepareStatement(base + " AND (sort_micros < ? OR (sort_micros=? AND conversation_id < ?))" + order)) {
                        query.setString(1, owner);
                        query.setLong(2, sort);
                        query.setLong(3, sort);
                        query.setString(4, conversation);
                        continuation = statistics(measure(query, count));
                    }
                System.out.println("{\"readOnly\":true,\"metadataTablePresent\":true,\"measured\":true,\"metadataRows\":" + total
                        + ",\"largestActiveOwnerRows\":" + ownerCount + ",\"selection\":\"largest_active_owner\",\"jdbcIncludesNetwork\":true,\"pooledAcquisitionMeasured\":false"
                        + ",\"firstPageSql\":" + statistics(first) + ",\"continuationSql\":" + continuation + ",\"plans\":[" + String.join(",", plans) + "]}");
                db.rollback();
            }
        } catch (Exception failure) {
            System.err.println("CONVERSATION_DIRECTORY_PROBE_FAILED type=" + failure.getClass().getSimpleName()
                    + (failure instanceof SQLException sql ? " sqlState=" + sql.getSQLState() + " vendorCode=" + sql.getErrorCode() : ""));
            System.exit(1);
        }
    }
}
