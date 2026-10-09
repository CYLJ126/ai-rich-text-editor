package com.arte.core.pojo.platform;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

class PlatformPackageBoundaryTest {
    private static final Path SOURCE = Path.of("src/main/java/com/arte/core");

    @Test
    void newDomainAndContractsHaveNoReverseDependenciesOrReactiveOrPersistenceTypes() throws Exception {
        for (String area : List.of("pojo/platform", "pojo/workspace", "pojo/access",
                "api/platform", "api/workspace", "api/access", "spi/platform", "spi/workspace", "spi/access",
                "application/platform", "application/workspace", "application/access")) {
            try (var paths = Files.walk(SOURCE.resolve(area))) {
                for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                    for (String line : Files.readAllLines(path)) {
                        if (!line.startsWith("import ")) continue;
                        for (String forbidden : List.of("com.arte.ainew.", "com.arte.ai.", "com.arte.app.",
                                "reactor.", "org.springframework.", "com.baomidou.", "jakarta.persistence.")) {
                            assertFalse(line.contains(forbidden), path + ": " + line);
                        }
                    }
                }
            }
        }
    }

    @Test
    void roleAndStateEnumsStayInEnumsPackagesAndPublicApisContainNoStubImplementation() throws Exception {
        for (String area : List.of("pojo/platform", "pojo/workspace", "pojo/access", "api/platform",
                "api/workspace", "api/access", "spi/platform", "spi/workspace", "spi/access")) {
            try (var paths = Files.walk(SOURCE.resolve(area))) {
                for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                    var source = Files.readString(path);
                    assertFalse(source.matches("(?s).*\\benum\\s+[A-Za-z].*"), path.toString());
                    assertFalse(source.contains("return null;"), path.toString());
                    assertFalse(source.contains("UnsupportedOperationException"), path.toString());
                }
            }
        }
    }
}
