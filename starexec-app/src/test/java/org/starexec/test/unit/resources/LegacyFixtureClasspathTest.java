package org.starexec.test.unit.resources;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.starexec.test.resources.ResourceLoader;

import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks real Maven resource discovery and deterministic generated ZIP fixtures
 * on the classpath without starting the legacy TestSequence lifecycle.
 */
public class LegacyFixtureClasspathTest {

    private static File fixture(String name) {
        URL resource = ResourceLoader.class.getResource("/org/starexec/test/resources/" + name);
        assertNotNull(resource, "Missing legacy test resource on Maven classpath: " + name);
        File file = new ResourceLoader().getResource(name);
        assertTrue(file.isFile() && file.canRead(), "Fixture must be a readable file: " + name);
        return file;
    }

    private static Map<String, ZipArchiveEntry> inspectZip(String archiveName) throws Exception {
        File archive = fixture(archiveName);
        assertTrue(Files.size(archive.toPath()) > 0, "Archive must not be empty: " + archiveName);
        Map<String, ZipArchiveEntry> entries = new HashMap<>();
        try (ZipFile zip = new ZipFile(archive)) {
            var en = zip.getEntries();
            while (en.hasMoreElements()) {
                ZipArchiveEntry entry = en.nextElement();
                entries.put(entry.getName(), entry);
            }
        }
        return entries;
    }

    private static String readZipEntry(String archiveName, String entryName) throws Exception {
        File archive = fixture(archiveName);
        try (ZipFile zip = new ZipFile(archive)) {
            ZipArchiveEntry entry = zip.getEntry(entryName);
            assertNotNull(entry, "Missing entry " + entryName + " in " + archiveName);
            try (InputStream in = zip.getInputStream(entry)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }

    @Test
    public void textResourcesAreOnClasspathAndRawFixtureSourceTreeIsExcluded() throws Exception {
        assertTrue(Files.size(fixture("CVC4Config.txt").toPath()) > 0);
        assertTrue(Files.size(fixture("postproc.sh").toPath()) > 0);

        String basicXml = Files.readString(fixture("jobXmls/basicJobXML.xml").toPath(), StandardCharsets.UTF_8);
        for (String field : new String[]{"SCHEMA_LOC", "BENCH_ONE", "CONFIG_ONE"}) {
            assertTrue(basicXml.contains("$$" + field + "$$"), "Missing basic job template field: " + field);
        }

        String pipelineXml = Files.readString(fixture("jobXmls/jobXML.xml").toPath(), StandardCharsets.UTF_8);
        for (String field : new String[]{"SCHEMA_LOC", "BENCH_ONE", "BENCH_TWO", "CONFIG_ONE", "CONFIG_TWO"}) {
            assertTrue(pipelineXml.contains("$$" + field + "$$"), "Missing pipeline job template field: " + field);
        }

        assertNull(
            ResourceLoader.class.getResource("/org/starexec/test/resources/fixtures-src/benchproc/process"),
            "Raw fixtures-src files should not be copied alongside generated archives"
        );
    }

    @Test
    public void generatedZipFixturesHaveExpectedEntriesAndExecutableModes() throws Exception {
        Map<String, ZipArchiveEntry> benchproc = inspectZip("benchproc.zip");
        assertEquals(Set.of("process"), benchproc.keySet());
        assertTrue((benchproc.get("process").getUnixMode() & 0111) != 0, "benchproc/process must be executable");
        String benchProcScript = readZipEntry("benchproc.zip", "process");
        assertTrue(benchProcScript.contains("starexec-valid=true"));
        assertTrue(benchProcScript.contains("test-attribute=test"));

        Map<String, ZipArchiveEntry> postproc = inspectZip("postproc.zip");
        assertEquals(Set.of("mybin/hello.txt", "process"), postproc.keySet());
        assertTrue((postproc.get("process").getUnixMode() & 0111) != 0, "postproc/process must be executable");

        Map<String, ZipArchiveEntry> benchmarks = inspectZip("benchmarks.zip");
        assertEquals(
            Set.of(
                "test/sat.smt2",
                "test/unsat.smt2",
                "test/unknown.smt2",
                "test/starexec_description.txt"
            ),
            benchmarks.keySet()
        );

        Map<String, ZipArchiveEntry> cvc4 = inspectZip("CVC4.zip");
        assertEquals(Set.of("bin/starexec_run_default", "starexec_description.txt"), cvc4.keySet());
        assertTrue(
            (cvc4.get("bin/starexec_run_default").getUnixMode() & 0111) != 0,
            "CVC4 bin/starexec_run_default must be executable"
        );

        Map<String, ZipArchiveEntry> smallsolver = inspectZip("smallsolver.zip");
        assertEquals(Set.of("bin/starexec_run_default"), smallsolver.keySet());
        assertTrue(
            (smallsolver.get("bin/starexec_run_default").getUnixMode() & 0111) != 0,
            "smallsolver bin/starexec_run_default must be executable"
        );
    }

    @Test
    public void missingResourceThrowsClearErrorWithLogicalNameAndClasspathPath() {
        ResourceLoader loader = new ResourceLoader();
        IllegalStateException directErr = assertThrows(
            IllegalStateException.class,
            () -> loader.getResource("nonexistent-fixture.zip")
        );
        assertTrue(directErr.getMessage().contains("nonexistent-fixture.zip"), directErr.getMessage());
        assertTrue(
            directErr.getMessage().contains("/org/starexec/test/resources/nonexistent-fixture.zip"),
            directErr.getMessage()
        );

        IllegalStateException loadErr = assertThrows(
            IllegalStateException.class,
            () -> loader.loadSolverIntoDatabase("nonexistent-solver.zip", 1, 1)
        );
        assertTrue(loadErr.getMessage().contains("nonexistent-solver.zip"), loadErr.getMessage());
    }

    @Test
    public void resolvesFileUrlsInsideDirectoriesWithSpaces(@TempDir Path tempDir) throws Exception {
        Path spacedDir = Files.createDirectory(tempDir.resolve("StarExec Containerised"));
        Path fixtureFile = spacedDir.resolve("CVC4+fixture.txt");
        Files.writeString(fixtureFile, "config-contents\n", StandardCharsets.UTF_8);

        URL encodedUrl = fixtureFile.toUri().toURL();
        assertTrue(encodedUrl.toString().contains("StarExec%20Containerised"));
        File resolvedEncoded = ResourceLoader.toFile(encodedUrl);
        assertTrue(resolvedEncoded.isFile() && resolvedEncoded.canRead(), resolvedEncoded.getPath());
        assertEquals("config-contents\n", Files.readString(resolvedEncoded.toPath(), StandardCharsets.UTF_8));

        // Also cover unencoded file: URLs that trigger the URISyntaxException fallback.
        URL rawSpacedUrl = new URL("file:" + fixtureFile.toAbsolutePath());
        File resolvedFallback = ResourceLoader.toFile(rawSpacedUrl);
        assertTrue(resolvedFallback.isFile() && resolvedFallback.canRead(), resolvedFallback.getPath());
        assertEquals("config-contents\n", Files.readString(resolvedFallback.toPath(), StandardCharsets.UTF_8));
        assertFalse(new ResourceLoader().getResourcePath().contains("%20"));
    }
}
