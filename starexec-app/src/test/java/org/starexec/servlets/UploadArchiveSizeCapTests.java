package org.starexec.servlets;

import org.junit.Test;

import javax.servlet.annotation.MultipartConfig;

import static org.junit.Assert.assertEquals;

/**
 * Solver and processor archives fetched by URL are capped at the same size as the archive the
 * upload form accepts, from one constant per servlet.
 *
 * <p>This pins the constant against the form limit. That each servlet passes it to
 * {@code Util.copyFileFromURLUsingProxy} is not driven here: reaching the download needs the
 * space or community permission checks, the database and the solver or processor directories.
 */
public class UploadArchiveSizeCapTests {

	@Test
	public void solverUrlDownloadsShareTheFormLimit() {
		assertEquals(1024L * 1024 * 1024, UploadSolver.MAX_ARCHIVE_BYTES);
		assertEquals(UploadSolver.MAX_ARCHIVE_BYTES,
				UploadSolver.class.getAnnotation(MultipartConfig.class).maxFileSize());
	}

	@Test
	public void processorUrlDownloadsShareTheFormLimit() {
		assertEquals(512L * 1024 * 1024, ProcessorManager.MAX_ARCHIVE_BYTES);
		assertEquals(ProcessorManager.MAX_ARCHIVE_BYTES,
				ProcessorManager.class.getAnnotation(MultipartConfig.class).maxFileSize());
	}
}
