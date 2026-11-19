package org.starexec.test.junit.servlets;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.data.database.Permissions;
import org.starexec.data.security.ValidatorStatusCode;
import org.starexec.data.to.Permission;
import org.starexec.servlets.UploadBenchmark;
import org.starexec.util.PartWrapper;
import org.starexec.util.SessionUtil;
import org.starexec.util.Validator;

import javax.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;

/**
 * An upload with dependencies reads from a second space, the dependency root: includes resolve
 * against the benchmarks in it. Request validation checked only the target space, so the root
 * could be any space id, seen or not.
 */
public class UploadBenchmarkDependencyRootTest {

    private static final int TARGET_SPACE = 10;
    private static final int DEP_ROOT = 38;
    private static final int UPLOADER = 7;

    /** The application compiles these patterns at startup; validation uses them. */
    @BeforeAll
    public static void compileValidatorPatterns() {
        Validator.initialize();
    }

    private static HashMap<String, Object> form(String dependency, String depRoot) {
        HashMap<String, Object> form = new HashMap<>();
        form.put("benchType", "4");
        form.put("space", String.valueOf(TARGET_SPACE));
        form.put("download", "true");
        form.put("upMethod", "dump");
        form.put("localOrURLOrGit", "local");
        PartWrapper file = Mockito.mock(PartWrapper.class);
        Mockito.when(file.getName()).thenReturn("problems.tgz");
        form.put("benchFile", file);
        if (dependency != null) {
            form.put("dependency", dependency);
        }
        if (depRoot != null) {
            form.put("depRoot", depRoot);
        }
        return form;
    }

    /** Runs request validation for an uploader who may add benchmarks to the target space. */
    private static ValidatorStatusCode validate(HashMap<String, Object> form, boolean rootVisible,
                                                MockedStatic<Permissions> permissions) throws Exception {
        HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        Permission perm = Mockito.mock(Permission.class);
        Mockito.when(perm.canAddBenchmark()).thenReturn(true);
        try (MockedStatic<SessionUtil> session = Mockito.mockStatic(SessionUtil.class)) {
            session.when(() -> SessionUtil.getPermission(request, TARGET_SPACE)).thenReturn(perm);
            session.when(() -> SessionUtil.getUserId(request)).thenReturn(UPLOADER);
            permissions.when(() -> Permissions.canUserSeeSpace(DEP_ROOT, UPLOADER)).thenReturn(rootVisible);
            Method m = UploadBenchmark.class.getDeclaredMethod(
                "isRequestValid", HashMap.class, HttpServletRequest.class);
            m.setAccessible(true);
            return (ValidatorStatusCode) m.invoke(new UploadBenchmark(), form, request);
        }
    }

    @Test
    public void aDependencyRootTheUploaderCannotSeeIsRefused() throws Exception {
        try (MockedStatic<Permissions> permissions = Mockito.mockStatic(Permissions.class)) {
            ValidatorStatusCode status = validate(form("true", String.valueOf(DEP_ROOT)), false, permissions);

            assertFalse(status.isSuccess());
            assertEquals("You do not have permission to see the dependency root space", status.getMessage());
        }
    }

    @Test
    public void aDependencyRootTheUploaderCanSeeIsAccepted() throws Exception {
        try (MockedStatic<Permissions> permissions = Mockito.mockStatic(Permissions.class)) {
            assertTrue(validate(form("true", String.valueOf(DEP_ROOT)), true, permissions).isSuccess());
        }
    }

    /** Without dependencies the root is never read, so it is not consulted either. */
    @Test
    public void withoutDependenciesTheRootIsNotChecked() throws Exception {
        try (MockedStatic<Permissions> permissions = Mockito.mockStatic(Permissions.class)) {
            assertTrue(validate(form("false", String.valueOf(DEP_ROOT)), false, permissions).isSuccess());
            permissions.verify(() -> Permissions.canUserSeeSpace(anyInt(), anyInt()), Mockito.never());
        }
    }

    /** An absent root defaults to the target space, whose permission was already checked. */
    @Test
    public void anAbsentRootFallsBackToTheCheckedTargetSpace() throws Exception {
        try (MockedStatic<Permissions> permissions = Mockito.mockStatic(Permissions.class)) {
            assertTrue(validate(form("true", null), false, permissions).isSuccess());
            permissions.verify(() -> Permissions.canUserSeeSpace(anyInt(), anyInt()), Mockito.never());
        }
    }
}
