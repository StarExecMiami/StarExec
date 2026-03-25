package org.starexec.test.junit.app;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.app.RESTServices;
import org.starexec.data.security.UploadSecurity;

import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class RESTServicesUploadSessionTests {

	@Test
	public void createUploadSessionRejectsOversizedRequestBody() throws Exception {
		RESTServices services = new RESTServices();
		HttpServletRequest request = Mockito.mock(HttpServletRequest.class);

		byte[] oversized = new byte[(64 * 1024) + 1];
		for (int i = 0; i < oversized.length; i++) {
			oversized[i] = 'a';
		}

		ByteArrayInputStream bais = new ByteArrayInputStream(oversized);
		ServletInputStream sis = new ServletInputStream() {
			@Override
			public int read() {
				return bais.read();
			}

			@Override
			public boolean isFinished() {
				return bais.available() == 0;
			}

			@Override
			public boolean isReady() {
				return true;
			}

			@Override
			public void setReadListener(ReadListener readListener) {
				// Not used in tests.
			}
		};

		Mockito.when(request.getInputStream()).thenReturn(sis);

		try (MockedStatic<UploadSecurity> uploadSecurityMock = Mockito.mockStatic(UploadSecurity.class)) {
			uploadSecurityMock.when(UploadSecurity::uploadsFrozen).thenReturn(false);
			String response = services.createUploadSession(request);
			assertTrue(response.contains("\"success\":false"));
			assertTrue(response.contains("Invalid request body"));
		}
	}

	@Test
	public void moveWithAtomicFallbackNoReplaceDoesNotOverwriteTarget() throws Exception {
		Path tempDir = Files.createTempDirectory("rest-services-upload-session-tests");
		Path source = tempDir.resolve("source.bin");
		Path target = tempDir.resolve("target.bin");
		Files.write(source, "source-data".getBytes(StandardCharsets.UTF_8));
		Files.write(target, "target-data".getBytes(StandardCharsets.UTF_8));

		Method method = RESTServices.class.getDeclaredMethod(
				"moveWithAtomicFallbackNoReplace",
				java.nio.file.Path.class,
				java.nio.file.Path.class);
		method.setAccessible(true);

		boolean threw = false;
		try {
			method.invoke(null, source, target);
		} catch (InvocationTargetException e) {
			threw = e.getCause() instanceof java.nio.file.FileAlreadyExistsException;
		}

		assertTrue("Expected FileAlreadyExistsException when target exists", threw);
		assertTrue("Target file should remain present", Files.exists(target));
		assertTrue("Source file should remain present after failed move", Files.exists(source));
		assertTrue(new String(Files.readAllBytes(target), StandardCharsets.UTF_8).equals("target-data"));

		Files.deleteIfExists(source);
		Files.deleteIfExists(target);
		Files.deleteIfExists(tempDir);
	}
}
