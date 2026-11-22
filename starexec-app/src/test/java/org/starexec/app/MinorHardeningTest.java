package org.starexec.app;

import com.github.dockerjava.api.DockerClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.backend.PodmanBackend;
import org.starexec.command.Shell;
import org.starexec.data.database.Users;
import org.starexec.servlets.ProcessorManager;
import org.starexec.util.ArchiveUtil;
import org.starexec.util.SessionUtil;
import org.starexec.util.Util;
import org.starexec.util.Validator;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MinorHardeningTest {

	@BeforeAll
	public static void initValidator() {
		Validator.initialize();
	}

	@Test
	public void shellExitsCleanlyOnImmediateEof() {
		InputStream priorIn = System.in;
		try {
			System.setIn(new ByteArrayInputStream(new byte[0]));
			assertDoesNotThrow(() -> new Shell().runShell());
		} finally {
			System.setIn(priorIn);
		}
	}

	@Test
	public void processorManagerSanitizesInternalExceptionMessage() throws Exception {
		HttpServletRequest request = mock(HttpServletRequest.class);
		HttpServletResponse response = mock(HttpServletResponse.class);
		org.starexec.util.PartWrapper part = mock(org.starexec.util.PartWrapper.class);
		when(part.getName()).thenReturn("proc.tgz");
		HashMap<String, Object> form = new HashMap<>();
		form.put("name", "proc");
		form.put("desc", "desc");
		form.put("com", "1");
		form.put("type", "pre");
		form.put("uploadMethod", "local");
		form.put("file", part);

		try (MockedStatic<SessionUtil> session = Mockito.mockStatic(SessionUtil.class)) {
			session.when(() -> SessionUtil.getPermission(request, 1))
					.thenThrow(new RuntimeException("sensitive internal DB host details"));
			Method m = ProcessorManager.class.getDeclaredMethod(
					"handleAddRequest", HashMap.class, HttpServletRequest.class, HttpServletResponse.class);
			m.setAccessible(true);
			m.invoke(new ProcessorManager(), form, request, response);
			verify(response).sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Failed to add new processor.");
		}
	}

	@Test
	public void usersIsDiskQuotaExceededHandlesMissingUserSafely() {
		try (MockedStatic<Users> users = Mockito.mockStatic(Users.class, Mockito.CALLS_REAL_METHODS)) {
			users.when(() -> Users.get(999)).thenReturn(null);
			assertFalse(Users.isDiskQuotaExceeded(999));
		}
	}

	@Test
	public void archiveAddDirHandlesUnreadableOrMissingDirectory(@TempDir Path tempDir) throws Exception {
		File missing = tempDir.resolve("does-not-exist").toFile();
		try (var zos = new org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream(
				new ByteArrayOutputStream())) {
			assertEquals(42L, ArchiveUtil.addDirToArchive(zos, missing, "root", 42L));
		}
	}

	@Test
	public void podmanEventListenerDoesNotReconnectWhenShuttingDown() throws Exception {
		PodmanBackend backend = new PodmanBackend();
		DockerClient client = mock(DockerClient.class);
		Field dockerField = PodmanBackend.class.getDeclaredField("dockerClient");
		dockerField.setAccessible(true);
		dockerField.set(backend, client);

		Field shuttingDownField = PodmanBackend.class.getDeclaredField("shuttingDown");
		shuttingDownField.setAccessible(true);
		shuttingDownField.set(backend, true);

		var eventsCmd = mock(com.github.dockerjava.api.command.EventsCmd.class);
		when(client.eventsCmd()).thenReturn(eventsCmd);
		when(eventsCmd.withEventTypeFilter("container")).thenReturn(eventsCmd);
		when(eventsCmd.withEventFilter("start", "die")).thenReturn(eventsCmd);
		when(eventsCmd.exec(any())).thenAnswer(inv -> {
			com.github.dockerjava.api.async.ResultCallback<com.github.dockerjava.api.model.Event> cb = inv.getArgument(0);
			cb.onError(new RuntimeException("stream closed"));
			return cb;
		});

		Method start = PodmanBackend.class.getDeclaredMethod("startContainerEventListener");
		start.setAccessible(true);
		assertDoesNotThrow(() -> start.invoke(backend));
		Mockito.verify(client, Mockito.times(1)).eventsCmd();
	}
}
