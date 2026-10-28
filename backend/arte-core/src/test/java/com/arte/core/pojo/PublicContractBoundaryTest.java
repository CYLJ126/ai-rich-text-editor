package com.arte.core.pojo;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

class PublicContractBoundaryTest {

    @Test
    void migratedContractsAndUtilitiesHaveNoBusinessOrRuntimeDependencies() throws Exception {
        Path root = Path.of("src/main/java/com/arte/core");
        for (String area : List.of("pojo/reference", "pojo/value", "utils/validation")) {
            try (var paths = Files.walk(root.resolve(area))) {
                for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                    check(path);
                    for (String line : Files.readAllLines(path)) {
                        if (line.startsWith("import ")) {
                            assertFalse(line.contains("jackson"), path + ": " + line);
                        }
                    }
                }
            }
        }
        check(root.resolve("serialize/CanonicalJson.java"));
        check(root.resolve("utils/ContentDigests.java"));
    }

    private static void check(Path path) throws Exception {
        for (String line : Files.readAllLines(path)) {
            if (!line.startsWith("import ")) continue;
            for (String forbidden : List.of("com.arte.ainew.", "com.arte.ai.", "com.arte.app.",
                    "reactor.", "org.springframework.", "com.baomidou.", "jakarta.persistence.")) {
                assertFalse(line.contains(forbidden), path + ": " + line);
            }
        }
    }
}
