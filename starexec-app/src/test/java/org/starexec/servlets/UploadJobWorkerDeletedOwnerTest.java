package org.starexec.servlets;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.io.FileUtils;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.data.database.UploadJobQueue;
import org.starexec.data.database.Users;
import org.starexec.data.to.UploadJob;
import org.starexec.data.to.User;
import org.starexec.util.UploadArtifactPathGuard;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mockStatic;

/**
 * An upload job whose user is deleted while the worker downloads or extracts its archive leaves
 * nothing behind (#275).
 *
 * <p>A user deletion removes the user's directory under the benchmark root and, through the
 * foreign keys, the upload job's row. The worker can still be writing at that moment: the download
 * creates its directory only once the server has answered, and the extraction creates each entry's
 * directory as it goes. Both create the user's directory again. A failed job keeps its files for a
 * retry, but a job whose row is gone is never retried, so whatever it wrote stays on the volume,
 * owned by no one.
 *
 * <p>Each test runs one job through the worker's real processJob. The database is replaced at its
 * static boundary; the files are real, under a temporary benchmark root.
 */
public class UploadJobWorkerDeletedOwnerTest {

	private static final int OWNER = 7;
	private static final long JOB_ID = 42L;
	private static final String URL = "http://example.org/archives/probe.tar.gz";

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private final AtomicBoolean ownerExists = new AtomicBoolean(true);
	private final AtomicInteger touches = new AtomicInteger();
	private File root;
	private Path archive;
	private File neighbour;
	private long diskQuota = 1L << 30;
	/** What Users.exists throws, if anything: a lookup that fails. */
	private Exception existsFailure;
	/** Runs on the first heartbeat after the job started: the extraction is under way. */
	private Runnable duringExtraction = () -> { };

	@Before
	public void setUp() throws IOException {
		root = tmp.newFolder("Benchmarks");
		archive = root.toPath().resolve(OWNER + "/20261015-17.00.00.000/probe.tar.gz");
		Files.createDirectories(archive.getParent());
		neighbour = new File(root, "6/20261018-10.00.00.000/other.p");
		Files.createDirectories(neighbour.getParentFile().toPath());
		Files.writeString(neighbour.toPath(), "fof(b, axiom, $true).\n");
	}

	/** The race from #275 on an upload by URL: the user is deleted while the server is answering. */
	@Test
	public void aUserDeletedDuringTheDownloadLeavesNothing() throws Exception {
		UploadJobWorker worker = worker();
		worker.archiveFetcher = (url, target, maxBytes) -> {
			deleteOwner();
			// As ArchiveUrlDownloader does: its directory is created once the response has started.
			try {
				Files.createDirectories(target.toPath().getParent());
				writeArchive(target.toPath(), 3);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
			return true;
		};

		process(worker, job(URL));

		assertNothingLeftForTheOwner();
	}

	/** The race from #275 during extraction: each later entry creates the user's directory again. */
	@Test
	public void aUserDeletedDuringExtractionLeavesNothing() throws Exception {
		writeArchive(archive, 3);
		duringExtraction = this::deleteOwner;

		process(worker(), job(null));

		assertTrue("the deletion ran while entries were still being extracted", touches.get() >= 2);
		assertNothingLeftForTheOwner();
	}

	/** A job that fails while its user exists keeps its archive, so that a retry can use it. */
	@Test
	public void aFailedJobOfAnExistingUserKeepsItsArchiveForARetry() throws Exception {
		writeArchive(archive, 3);
		diskQuota = 1;

		process(worker(), job(null));

		assertTrue("the archive is kept for a retry", Files.isRegularFile(archive));
	}

	/**
	 * Users.get returns null on a database error as well as for a deleted user. A failed lookup
	 * must not be taken for a deletion: the job's files would be deleted on a guess.
	 */
	@Test
	public void aDatabaseErrorIsNotTakenForADeletedUser() throws Exception {
		writeArchive(archive, 3);
		ownerExists.set(false);
		existsFailure = new SQLException("synthetic connection failure");

		process(worker(), job(null));

		assertTrue("the archive is kept for a retry", Files.isRegularFile(archive));
	}

	/**
	 * Nor is a lookup that cannot run at all, such as one with no connection pool, and it does not
	 * end the job with an exception of its own: the lookup is made in cleanup code.
	 */
	@Test
	public void aLookupThatCannotRunIsNotTakenForADeletedUser() throws Exception {
		writeArchive(archive, 3);
		ownerExists.set(false);
		existsFailure = new IllegalStateException("no connection pool");

		process(worker(), job(null));

		assertTrue("the archive is kept for a retry", Files.isRegularFile(archive));
	}

	/**
	 * Only what the job wrote is deleted: its archive and the directories its extractions made,
	 * finished or not. Another job's directory beside them is kept (uploads made before each got a
	 * directory of its own could share one), and so are the directories above, which are not empty.
	 */
	@Test
	public void onlyTheFilesOfTheJobAreDeleted() throws Exception {
		writeArchive(archive, 3);
		Path directory = archive.getParent();
		Path unfinished = write(directory.resolve("upload_" + JOB_ID + "_1.extracting/Problems/a.p"));
		Path finished = write(directory.resolve("upload_" + JOB_ID + "_2/Problems/b.p"));
		Path anotherJobs = write(directory.resolve("upload_" + JOB_ID + "1_3/Problems/c.p"));
		ownerExists.set(false);

		process(worker(), job(null));

		assertFalse("the archive is deleted", Files.exists(archive));
		assertFalse("an unfinished extraction is deleted", Files.exists(unfinished.getParent().getParent()));
		assertFalse("a finished extraction is deleted", Files.exists(finished.getParent().getParent()));
		assertTrue("another job's files are kept", Files.isRegularFile(anotherJobs));
	}

	/** Users.deleteUser's end: the row is gone and the user's benchmark directory is deleted. */
	private void deleteOwner() {
		ownerExists.set(false);
		try {
			FileUtils.deleteDirectory(new File(root, "" + OWNER));
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	private UploadJobWorker worker() throws IOException {
		UploadJobWorker worker = new UploadJobWorker(new UploadArtifactPathGuard(root.toPath()));
		// Every progress callback is a second apart, so each extracted entry sends a heartbeat.
		AtomicLong now = new AtomicLong();
		worker.nanoTime = () -> now.addAndGet(2_000_000_000L);
		return worker;
	}

	private UploadJob job(String sourceUrl) {
		UploadJob job = new UploadJob();
		job.setId(JOB_ID);
		job.setUserId(OWNER);
		job.setArchivePath(archive.toAbsolutePath().toString());
		job.setUploadMethod("dump");
		job.setBenchmarkTypeId(1);
		job.setSourceUrl(sourceUrl);
		job.setRetryCount(0);
		job.setMaxRetries(3);
		return job;
	}

	private void process(UploadJobWorker worker, UploadJob job) throws Exception {
		User owner = new User();
		owner.setId(OWNER);
		owner.setDiskQuota(diskQuota);
		owner.setDiskUsage(0);
		try (MockedStatic<Users> users = mockStatic(Users.class, invocation -> {
			     switch (invocation.getMethod().getName()) {
			     case "get":
				     return ownerExists.get() ? owner : null;
			     case "exists":
				     if (existsFailure != null) {
					     throw existsFailure;
				     }
				     return ownerExists.get();
			     default:
				     return Mockito.RETURNS_DEFAULTS.answer(invocation);
			     }
		     });
		     MockedStatic<UploadJobQueue> queue = mockStatic(UploadJobQueue.class, invocation -> {
			     switch (invocation.getMethod().getName()) {
			     case "touchJob":
				     // The first is processJob's own; the next ones come from the extraction.
				     if (touches.incrementAndGet() == 2) {
					     duringExtraction.run();
				     }
				     return true;
			     case "updateExtractPath":
				     // The job's row goes with its user.
				     return ownerExists.get();
			     default:
				     return Mockito.RETURNS_DEFAULTS.answer(invocation);
			     }
		     })) {
			Method processJob = UploadJobWorker.class.getDeclaredMethod("processJob", UploadJob.class);
			processJob.setAccessible(true);
			processJob.invoke(worker, job);
			queue.verify(() -> UploadJobQueue.failJob(Mockito.eq(JOB_ID), Mockito.anyString()));
		}
	}

	private void assertNothingLeftForTheOwner() {
		assertFalse("nothing is left under the deleted user's benchmark directory",
				new File(root, "" + OWNER).exists());
		assertTrue("another user's benchmark is untouched", neighbour.isFile());
		assertTrue("the benchmark root is kept", root.isDirectory());
	}

	private static Path write(Path file) throws IOException {
		Files.createDirectories(file.getParent());
		return Files.writeString(file, "fof(c, axiom, $true).\n");
	}

	private static void writeArchive(Path target, int entries) throws IOException {
		try (OutputStream out = Files.newOutputStream(target);
		     GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(out);
		     TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
			for (int i = 0; i < entries; i++) {
				byte[] bytes = ("fof(a" + i + ", axiom, $true).\n").getBytes(StandardCharsets.UTF_8);
				TarArchiveEntry entry = new TarArchiveEntry("Problems/PRB00" + i + "+1.p");
				entry.setSize(bytes.length);
				tar.putArchiveEntry(entry);
				tar.write(bytes);
				tar.closeArchiveEntry();
			}
			tar.finish();
		}
	}
}
