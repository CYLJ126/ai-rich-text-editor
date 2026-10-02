package com.arte.app.testsupport;

import org.springframework.core.io.ByteArrayResource;

import java.nio.charset.StandardCharsets;

/**
 * Keeps production MySQL DDL usable in H2 tests without depending on case or line breaks.
 */
public final class MySqlTestScripts {
    private MySqlTestScripts() {
    }

    public static ByteArrayResource h2Resource(String sql) {
        String h2 = sql.replaceAll(
                        "(?i)\\s+engine\\s*=\\s*InnoDB\\s+default\\s+charset\\s*=\\s*utf8mb4\\s+collate\\s*=\\s*utf8mb4_bin", "")
                .replaceAll("(?i)\\)\\s+stored\\b", ")");
        return new ByteArrayResource(h2.getBytes(StandardCharsets.UTF_8));
    }
}
