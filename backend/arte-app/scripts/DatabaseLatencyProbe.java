import java.nio.file.*;
import java.sql.*;
import java.util.*;

/**
 * 仅 SELECT 1 与只读事务提交；凭据从本机配置/环境读取，不进入参数、日志或报告。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/14 2210:56 ✾
 */
public class DatabaseLatencyProbe {
    private static String setting(Properties file, String suffix) {
        return System.getenv().getOrDefault("SPRING_DATASOURCE_DRUID_APP_" + suffix.toUpperCase(Locale.ROOT),
                file.getProperty("spring.datasource.druid.app." + suffix, ""));
    }

    private static double ms(long start) {
        return (System.nanoTime() - start) / 1_000_000.0;
    }

    private static String stats(List<Double> samples) {
        var sorted = samples.stream().sorted().toList();
        return String.format(Locale.ROOT, "{\"samples\":%d,\"minMs\":%.3f,\"p50Ms\":%.3f,\"p95Ms\":%.3f,\"maxMs\":%.3f}",
                sorted.size(), sorted.getFirst(), sorted.get((sorted.size() - 1) / 2),
                sorted.get((int) Math.ceil(sorted.size() * .95) - 1), sorted.getLast());
    }

    public static void main(String[] args) {
        try {
            int count = args.length > 1 ? Integer.parseInt(args[1]) : 30;
            if (count < 5 || count > 100) throw new IllegalArgumentException("sample count");
            var file = new Properties();
            try (var reader = Files.newBufferedReader(Path.of(args.length > 0 ? args[0] : "profile/app.properties"))) {
                file.load(reader);
            }
            String url = setting(file, "url");
            if (!url.startsWith("jdbc:mysql:")) throw new IllegalArgumentException("MySQL URL required");
            var credentials = new Properties();
            credentials.setProperty("user", setting(file, "username"));
            credentials.setProperty("password", setting(file, "password"));
            credentials.setProperty("connectTimeout", "8000");
            credentials.setProperty("socketTimeout", "5000");
            DriverManager.setLoginTimeout(10);
            long began = System.nanoTime();
            try (var connection = DriverManager.getConnection(url, credentials)) {
                double connect = ms(began);
                connection.setReadOnly(true);
                try (var statement = connection.prepareStatement("SELECT 1")) {
                    for (int i = 0; i < 3; i++)
                        try (var rows = statement.executeQuery()) {
                            rows.next();
                        }
                    var query = new ArrayList<Double>();
                    var transaction = new ArrayList<Double>();
                    for (int i = 0; i < count; i++) {
                        began = System.nanoTime();
                        try (var rows = statement.executeQuery()) {
                            if (!rows.next() || rows.getInt(1) != 1) throw new SQLException("probe");
                        }
                        query.add(ms(began));
                    }
                    connection.setAutoCommit(false);
                    for (int i = 0; i < count; i++) {
                        began = System.nanoTime();
                        try (var rows = statement.executeQuery()) {
                            rows.next();
                        }
                        connection.commit();
                        transaction.add(ms(began));
                    }
                    connection.rollback();
                    System.out.printf(Locale.ROOT,
                            "{\"date\":\"%s\",\"readOnly\":true,\"sql\":\"SELECT 1\",\"freshConnectionMs\":%.3f,\"warmQuery\":%s,\"queryAndCommit\":%s,\"pooledAcquisitionMeasured\":false}%n",
                            java.time.LocalDate.now(), connect, stats(query), stats(transaction));
                }
            }
        } catch (Exception error) {
            System.err.println("DATABASE_LATENCY_PROBE_FAILED type=" + error.getClass().getSimpleName()
                    + (error instanceof SQLException sql ? " sqlState=" + sql.getSQLState() : ""));
            System.exit(2);
        }
    }
}
