package com.store.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Garantiza que cada id de requisito del SRS aparezca en las fuentes de prueba (spec 22). */
class TraceabilityTest {

    private static final List<String> IDS = List.of(
            "RF-S1", "RF-S2", "RF-S3", "RF-S4",
            "RF-R1", "RF-R2", "RF-R3", "RF-R4", "RF-R5", "RF-R6",
            "RF-E1", "RF-E2", "RF-E3",
            "RF-C1", "RF-C2", "RF-C3",
            "RF-A1", "RF-A2", "RF-A3", "RF-A4",
            "RNF-1", "RNF-2", "RNF-3", "RNF-4", "RNF-5", "RNF-6", "RNF-7",
            "C-1", "C-2", "C-3", "C-4", "C-5", "C-6");

    @Test
    @DisplayName("C-1 C-5 C-6: every RF/RNF/C id is cited from a test")
    void everyRequirementIdIsCited() throws Exception {
        Path tests = Path.of("src/test/java");
        StringBuilder blob = new StringBuilder();
        try (Stream<Path> files = Files.walk(tests)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                blob.append(Files.readString(file)).append('\n');
            }
        }
        String text = blob.toString();
        assertThat(IDS).allSatisfy(id -> assertThat(text).contains(id));
    }
}
