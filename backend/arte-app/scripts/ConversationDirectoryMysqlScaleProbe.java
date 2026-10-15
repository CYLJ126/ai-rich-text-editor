import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/**
 * Synthetic SQL capacity baseline in a connection-local temporary table on loopback MySQL only.
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/15 13:50 ✾
 */
public class ConversationDirectoryMysqlScaleProbe {
    static final String TABLE = "arte_layout_metadata_probe";
    static final String COLUMNS = "id_key,owner_key,conversation_id,title,metadata_version,pinned,title_manually_edited,auto_title_resolved,preview_text,user_message_count,last_message_micros,sort_micros,created_micros,statistics_ready";
    static final String BASE = "SELECT " + COLUMNS + " FROM " + TABLE + " WHERE owner_key='layout-probe' AND pinned=? AND active=1";
    static final String ORDER = " ORDER BY sort_micros DESC,conversation_id DESC LIMIT ?";

    interface Sample {
        void run() throws SQLException;
    }

    static String measure(Sample operation, int samples) throws SQLException {
        var values = new ArrayList<Double>();
        for (int i = -5; i < samples; i++) {
            long started = System.nanoTime();
            operation.run();
            if (i >= 0) values.add((System.nanoTime() - started) / 1_000_000.0);
        }
        Collections.sort(values);
        return String.format(Locale.ROOT, "{\"samples\":%d,\"p50Ms\":%.3f,\"p95Ms\":%.3f,\"maxMs\":%.3f}",
                samples, values.get((int) Math.ceil(samples * .5) - 1), values.get((int) Math.ceil(samples * .95) - 1), values.getLast());
    }

    static void read(PreparedStatement statement) throws SQLException {
        try (var rows = statement.executeQuery()) {
            while (rows.next()) rows.getObject(1);
        }
    }

    public static void main(String[] args) {
        try {
            var config = new Properties();
            try (var file = Files.newBufferedReader(Path.of(args[0]))) {
                config.load(file);
            }
            String url = config.getProperty("spring.datasource.druid.app.url");
            if (!(url.startsWith("jdbc:mysql://127.0.0.1:") || url.startsWith("jdbc:mysql://localhost:")))
                throw new IllegalArgumentException("Dedicated loopback MySQL required");
            int samples = args.length > 2 ? Integer.parseInt(args[2]) : 30;
            if (samples < 5 || samples > 100) throw new IllegalArgumentException("samples must be 5..100");
            String ddl = Files.readString(Path.of(args[1]));
            int start = ddl.indexOf("create table arte_ai_conversation_metadata");
            ddl = ddl.substring(start, ddl.indexOf(';', start));
            ddl = ddl.replace("create table arte_ai_conversation_metadata", "create temporary table " + TABLE);
            var cases = new ArrayList<String>();
            try (var db = DriverManager.getConnection(url, config.getProperty("spring.datasource.druid.app.username"),
                    config.getProperty("spring.datasource.druid.app.password"))) {
                try (var statement = db.createStatement()) {
                    statement.execute(ddl);
                }
                for (int count : new int[]{1000, 10000})
                    for (int pins : new int[]{0, 7})
                        for (int messages : new int[]{0, 10000}) {
                            try (var statement = db.createStatement()) {
                                statement.execute("TRUNCATE TABLE " + TABLE);
                            }
                            db.setAutoCommit(false);
                            try (var insert = db.prepareStatement("INSERT INTO " + TABLE + " (id_key,owner_key,conversation_id,title,active,metadata_version,pinned,title_manually_edited,auto_title_resolved,preview_text,user_message_count,last_message_micros,sort_micros,created_micros,statistics_ready) VALUES (?, 'layout-probe', ?, '合成会话', 1, 0, ?, 0, 1, '合成问题摘要', ?, ?, ?, 1, 1)")) {
                                for (int n = 0; n < count; n++) {
                                    String id = String.format(Locale.ROOT, "%064d", n);
                                    insert.setString(1, id);
                                    insert.setString(2, "synthetic-" + id);
                                    insert.setBoolean(3, n < pins);
                                    insert.setInt(4, messages);
                                    insert.setLong(5, count - n);
                                    insert.setLong(6, count - n);
                                    insert.addBatch();
                                    if (n % 500 == 499) insert.executeBatch();
                                }
                            }
                            db.commit();
                            db.setAutoCommit(true);
                            try (var list = db.prepareStatement(BASE + ORDER);
                                 var total = db.prepareStatement("SELECT COUNT(*) FROM " + TABLE + " WHERE owner_key='layout-probe' AND pinned=? AND active=1");
                                 var next = db.prepareStatement(BASE + " AND (sort_micros < ? OR (sort_micros=? AND conversation_id < ?))" + ORDER)) {
                                String window = measure(() -> {
                                    for (boolean pinned : new boolean[]{true, false}) {
                                        list.setBoolean(1, pinned);
                                        list.setInt(2, pinned ? 8 : 21);
                                        read(list);
                                        total.setBoolean(1, pinned);
                                        read(total);
                                    }
                                }, samples);
                                next.setBoolean(1, false);
                                next.setLong(2, count / 2);
                                next.setLong(3, count / 2);
                                next.setString(4, "synthetic-" + String.format(Locale.ROOT, "%064d", count / 2));
                                next.setInt(5, 21);
                                String continuation = measure(() -> read(next), samples);
                                String index;
                                try (var explain = db.createStatement(); var rows = explain.executeQuery("EXPLAIN " + BASE.replace("?", "0") + ORDER.replace("?", "21"))) {
                                    rows.next();
                                    index = rows.getString("key");
                                }
                                cases.add("{\"rows\":" + count + ",\"pins\":" + pins + ",\"projectedUserMessagesPerConversation\":" + messages
                                        + ",\"firstWindowSqlStatements\":4,\"continuationSqlStatements\":1,\"index\":\"" + index
                                        + "\",\"firstWindow\":" + window + ",\"continuation\":" + continuation + "}");
                            }
                        }
                // TEMPORARY table is automatically removed when this connection closes.
            }
            System.out.println("{\"temporaryTableOnly\":true,\"loopbackMySqlOnly\":true,\"httpOrAuthorizationMeasured\":false,\"messageBodiesCreated\":false,\"cases\":[" + String.join(",", cases) + "]}");
        } catch (Exception error) {
            System.err.println("MYSQL_SCALE_PROBE_FAILED type=" + error.getClass().getSimpleName()
                    + (error instanceof SQLException sql ? " sqlState=" + sql.getSQLState() : ""));
            System.exit(1);
        }
    }
}
