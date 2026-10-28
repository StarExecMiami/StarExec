package org.starexec.servlets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.data.database.Spaces;
import org.starexec.data.to.Permission;
import org.starexec.data.to.UploadJob;
import org.starexec.util.UploadArtifactPathGuard;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;

/**
 * The default permissions of the spaces a "convert" upload creates are the ones the upload form
 * chose. They were computed and then dropped, and the worker gave every created space the same
 * five "add" permissions; StarexecCommand's withheld permissions ("false") also counted as granted.
 */
public class UploadSpacePermissionsTest {
    private Path root;

    @BeforeEach
    public void setUp() throws IOException {
        root = Files.createTempDirectory("space-permissions");
    }

    @AfterEach
    public void tearDown() throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        }
    }

    private static List<String> on(Permission p) {
        List<String> names = new ArrayList<>(p.getOnPermissions());
        Collections.sort(names);
        return names;
    }

    /** A multipart form from name/value pairs: form("addBench", "on", ...). */
    private static HashMap<String, Object> form(String... pairs) {
        if (pairs.length % 2 != 0) {
            throw new IllegalArgumentException("form() takes name/value pairs, got " + pairs.length + " strings");
        }
        HashMap<String, Object> form = new HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            form.put(pairs[i], pairs[i + 1]);
        }
        return form;
    }

    private static Permission extract(HashMap<String, Object> form) throws Exception {
        Method m = UploadBenchmark.class.getDeclaredMethod("extractPermissions", HashMap.class);
        m.setAccessible(true);
        return (Permission) m.invoke(new UploadBenchmark(), form);
    }

    @Test
    public void aCheckedCheckboxGrants() {
        // A browser sends a checked checkbox without a value attribute as "on".
        assertTrue(UploadBenchmark.granted(form("addBench", "on"), "addBench"));
        assertFalse(UploadBenchmark.granted(form(), "addBench"));
    }

    @Test
    public void starexecCommandsFalseWithholds() {
        assertFalse(UploadBenchmark.granted(form("addBench", "false"), "addBench"));
        assertFalse(UploadBenchmark.granted(form("addBench", " FALSE "), "addBench"));
        assertTrue(UploadBenchmark.granted(form("addBench", "true"), "addBench"));
    }

    @Test
    public void everyPermissionIsReadAsSent() throws Exception {
        // StarexecCommand's form: every permission, granted or not.
        HashMap<String, Object> sent = form(
            "addSolver", "false", "addBench", "true", "addUser", "false", "addSpace", "false",
            "addJob", "false", "removeSolver", "true", "removeBench", "false",
            "removeUser", "false", "removeSpace", "false", "removeJob", "false");
        assertEquals(Arrays.asList("addBench", "removeSolver"), on(extract(sent)));
    }

    @Test
    public void theWorkerUsesTheStoredPermissions() {
        UploadJob job = new UploadJob();
        Permission chosen = new Permission();
        chosen.setAddBenchmark(true);
        chosen.setRemoveSolver(true);
        job.setSpacePermissions(chosen);
        assertSame(chosen, UploadJobWorker.spacePermissionsFor(job));
    }

    @Test
    public void aJobQueuedBeforeThemKeepsTheOldDefault() {
        assertEquals(Arrays.asList("addBench", "addJob", "addSolver", "addSpace", "addUser"),
            on(UploadJobWorker.spacePermissionsFor(new UploadJob())));
    }

    @Test
    public void convertingGivesThemToTheSpacesItCreates() throws Exception {
        UploadJob job = new UploadJob();
        job.setId(42L);
        job.setUserId(7);
        job.setSpaceId(10);
        job.setUploadMethod("convert");
        Permission chosen = new Permission();
        chosen.setRemoveJob(true);
        job.setSpacePermissions(chosen);

        UploadJobWorker worker = new UploadJobWorker(new UploadArtifactPathGuard(root));
        Method convert = UploadJobWorker.class.getDeclaredMethod("handleConvertMethod", UploadJob.class, File.class);
        convert.setAccessible(true);
        try (MockedStatic<Spaces> spaces = Mockito.mockStatic(Spaces.class)) {
            convert.invoke(worker, job, root.toFile());
            ArgumentCaptor<Permission> given = ArgumentCaptor.forClass(Permission.class);
            spaces.verify(() -> Spaces.traverseAndAddBenchmarks(
                any(File.class), anyInt(), anyInt(), anyInt(), anyBoolean(), given.capture(),
                any(), any(), any(), any(), any(), any()));
            assertSame(chosen, given.getValue());
        }
    }
}
