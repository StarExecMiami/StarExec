package org.starexec.servlets;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.starexec.data.database.Benchmarks;
import org.starexec.data.database.ErrorLogs;
import org.starexec.data.to.Space;
import org.starexec.logger.StarLevel;
import org.starexec.util.Validator;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Checks text benchmark file creation on disposable files with mocked registration. */
public class QuickBenchmarkPathTest {

	@TempDir
	public Path temporary;

	@BeforeAll
	public static void initializeValidator() {
		Validator.initialize();
	}

	@Test
	public void validBenchmarkContentsRemainUtf8() throws Exception {
		Path directory = Files.createDirectory(temporary.resolve("upload"));
		String text = "synthetic benchmark \u03bb\n";
		assertEquals(Integer.valueOf(42), add(directory, "example benchmark.smt2", text));
		assertEquals(text, Files.readString(directory.resolve("example benchmark.smt2"), StandardCharsets.UTF_8));
	}

	@Test
	public void solverBuildHelperNameRemainsSupported() throws Exception {
		Path directory = Files.createDirectory(temporary.resolve("upload"));
		assertEquals(Integer.valueOf(42), add(directory, "starexec_build", "synthetic helper"));
		assertEquals("synthetic helper", Files.readString(directory.resolve("starexec_build")));
	}

	@Test
	public void nullContentsPreserveTheEmptyFileContract() throws Exception {
		Path directory = Files.createDirectory(temporary.resolve("upload"));
		assertEquals(Integer.valueOf(42), add(directory, "benchmark", null));
		assertEquals(0, Files.size(directory.resolve("benchmark")));
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = {"", ".", "..", "...", "../outside", "nested/name", "nested\\name", "/absolute"})
	public void malformedNamesCannotCreateAFile(String name) throws Exception {
		Path directory = Files.createDirectory(temporary.resolve("upload"));
		assertNull(add(directory, name, "synthetic benchmark"));
		try (var paths = Files.list(directory)) {
			assertEquals(0, paths.count());
		}
		assertFalse(Files.exists(temporary.resolve("outside")));
	}

	@Test
	public void anExistingFileCannotBeOverwritten() throws Exception {
		Path directory = Files.createDirectory(temporary.resolve("upload"));
		Path existing = Files.writeString(directory.resolve("benchmark"), "original");
		assertNull(add(directory, "benchmark", "replacement"));
		assertEquals("original", Files.readString(existing));
	}

	@Test
	public void aSymbolicLinkLeafCannotRedirectAWrite() throws Exception {
		Path directory = Files.createDirectory(temporary.resolve("upload"));
		Path outside = Files.writeString(temporary.resolve("outside"), "original");
		Files.createSymbolicLink(directory.resolve("benchmark"), outside);
		assertNull(add(directory, "benchmark", "replacement"));
		assertEquals("original", Files.readString(outside));
	}

	@Test
	public void aSymbolicLinkDirectoryCannotRedirectAWrite() throws Exception {
		Path outside = Files.createDirectory(temporary.resolve("outside"));
		Path directory = Files.createSymbolicLink(temporary.resolve("upload"), outside);
		assertNull(add(directory, "benchmark", "replacement"));
		assertFalse(Files.exists(outside.resolve("benchmark")));
	}

	@Test
	public void aSymbolicLinkAncestorCannotRedirectAWrite() throws Exception {
		Path outside = Files.createDirectory(temporary.resolve("outside"));
		Path child = Files.createDirectory(outside.resolve("child"));
		Path ancestor = Files.createSymbolicLink(temporary.resolve("upload"), outside);
		assertNull(add(ancestor.resolve("child"), "benchmark", "replacement"));
		assertFalse(Files.exists(child.resolve("benchmark")));
	}

	@Test
	public void aMissingAllocationCannotFallBackToTheWorkingDirectory() throws Exception {
		try (MockedStatic<org.apache.commons.io.FileUtils> writer = mockStatic(org.apache.commons.io.FileUtils.class)) {
			assertNull(add(null, "benchmark", "synthetic benchmark"));
			writer.verifyNoInteractions();
		}
	}

	private Integer add(Path directory, String name, String text) throws Exception {
		Space extracted = new Space();
		try (MockedStatic<UploadBenchmark> uploads = mockStatic(UploadBenchmark.class, invocation -> {
			if (invocation.getMethod().getName().equals("getDirectoryForBenchmarkUpload")) {
				return directory == null ? null : directory.toFile();
			}
			if (invocation.getMethod().getName().equals("deleteDirectoryForBenchmarkUpload")) {
				return null;
			}
			return invocation.callRealMethod();
		});
		     MockedStatic<Benchmarks> benchmarks = mockStatic(Benchmarks.class);
		     MockedStatic<ErrorLogs> errors = mockStatic(ErrorLogs.class)) {
			benchmarks.when(() -> Benchmarks.extractSpacesAndBenchmarks(any(), anyInt(), anyInt(), anyBoolean(), any(), any()))
					.thenReturn(extracted);
			benchmarks.when(() -> Benchmarks.processAndAdd(anyList(), any(), anyInt(), anyBoolean(), any()))
					.thenReturn(List.of(42));
			errors.when(() -> ErrorLogs.add(anyString(), any(StarLevel.class))).thenReturn(Optional.of(1));
			return UploadBenchmark.addBenchmarkFromText(text, name, 1, 1, false);
		}
	}
}
