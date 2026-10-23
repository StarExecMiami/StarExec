package org.starexec.servlets;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.io.FileUtils;
import org.junit.Assume;
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
import org.starexec.util.UploadArtifactFileSystem;
import org.starexec.util.UploadArtifactPathGuard;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
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
 * static boundary; the files are real, under a temporary benchmark root. Where a test needs a
 * deletion to fail, the file helper is replaced for that call and runs for real for every other.
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

	/**
	 * The archive path comes from the job's row, so the cleanup checks it first. One that is not
	 * where this user's uploads write, here another user's directory, deletes nothing: neither the
	 * archive there nor what is beside it.
	 */
	@Test
	public void anArchivePathOutsideTheUsersUploadsDeletesNothing() throws Exception {
		Path othersArchive = neighbour.toPath().resolveSibling("other.tar.gz");
		writeArchive(othersArchive, 1);
		Path besideIt = write(othersArchive.resolveSibling("upload_" + JOB_ID + "_1/Problems/a.p"));
		ownerExists.set(false);
		UploadJob job = job(null);
		job.setArchivePath(othersArchive.toString());

		process(worker(), job);

		assertTrue("the archive in another user's directory is kept", Files.isRegularFile(othersArchive));
		assertTrue("the files beside it are kept", Files.isRegularFile(besideIt));
		assertTrue("another user's benchmark is untouched", neighbour.isFile());
	}

	/** Nor does one that is not a path at all, and the cleanup throws nothing of its own. */
	@Test
	public void anArchivePathThatIsNotAPathDeletesNothing() throws Exception {
		writeArchive(archive, 1);
		ownerExists.set(false);
		UploadJob job = job(null);
		job.setArchivePath(archive + "\0");

		process(worker(), job);

		assertTrue("the archive is kept", Files.isRegularFile(archive));
	}

	/**
	 * Each extraction directory is deleted on its own: two that cannot be deleted, one with a
	 * checked and one with an unchecked exception, keep neither the third nor the archive.
	 */
	@Test
	public void anExtractionThatCannotBeDeletedDoesNotKeepTheRest() throws Exception {
		writeArchive(archive, 1);
		Path directory = archive.getParent();
		for (int i = 1; i <= 3; i++) {
			write(directory.resolve("upload_" + JOB_ID + "_" + i + "/Problems/a.p"));
		}
		ownerExists.set(false);
		AtomicInteger attempts = new AtomicInteger();

		try (MockedStatic<UploadArtifactFileSystem> files = mockStatic(UploadArtifactFileSystem.class, invocation -> {
			if (invocation.getMethod().getName().equals("deleteDirectoryWithoutFollowingLinks")) {
				int attempt = attempts.incrementAndGet();
				if (attempt == 1) {
					throw new IOException("synthetic: cannot delete");
				}
				if (attempt == 2) {
					throw new UncheckedIOException(new IOException("synthetic: cannot delete"));
				}
			}
			return invocation.callRealMethod();
		})) {
			process(worker(), job(null));
		}

		assertEquals("every extraction directory is tried", 3, attempts.get());
		assertEquals("only the two that could not be deleted are left", 2, extractions(directory).size());
		assertFalse("the archive is deleted", Files.exists(archive));
	}

	/**
	 * A link among the job's extraction directories is refused, not followed: what it points to,
	 * here another user's directory, is kept, and the archive is still deleted.
	 */
	@Test
	public void aLinkAmongTheExtractionsIsNotFollowed() throws Exception {
		writeArchive(archive, 1);
		try {
			Files.createSymbolicLink(archive.resolveSibling("upload_" + JOB_ID + "_1"), neighbour.toPath().getParent());
		} catch (UnsupportedOperationException | IOException e) {
			Assume.assumeNoException("this file store cannot create symlinks", e);
		}
		ownerExists.set(false);

		process(worker(), job(null));

		assertTrue("what the link points to is kept", neighbour.isFile());
		assertFalse("the archive is deleted", Files.exists(archive));
	}

	/**
	 * A directory that cannot be listed still loses the archive and then, once empty, itself and
	 * the user's directory: a failed listing skips only the extraction directories.
	 */
	@Test
	public void aDirectoryThatCannotBeListedStillLosesTheArchive() throws Exception {
		Path directory = archive.getParent();
		Assume.assumeTrue("POSIX file permissions", Files.getFileStore(directory).supportsFileAttributeView("posix"));
		writeArchive(archive, 1);
		ownerExists.set(false);
		Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("-wx------"));
		try {
			Assume.assumeFalse("file permissions apply to this user", Files.isReadable(directory));
			process(worker(), job(null));
		} finally {
			if (Files.exists(directory)) {
				Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
			}
		}

		assertFalse("the archive is deleted", Files.exists(archive));
		assertNothingLeftForTheOwner();
	}

	/**
	 * An archive that cannot be deleted does not end the job with an exception of the cleanup's
	 * own, and the extraction before it is still deleted. The failure injected is unchecked: the
	 * cleanup catches those too, so none can replace the exception that ended the job.
	 */
	@Test
	public void anArchiveThatCannotBeDeletedThrowsNothing() throws Exception {
		writeArchive(archive, 1);
		Path finished = write(archive.resolveSibling("upload_" + JOB_ID + "_1/Problems/b.p"));
		ownerExists.set(false);

		try (MockedStatic<UploadArtifactFileSystem> files = mockStatic(UploadArtifactFileSystem.class, invocation -> {
			if (invocation.getMethod().getName().equals("deleteFileWithoutFollowingLinks")
					&& archive.equals(invocation.getArgument(0))) {
				throw new UncheckedIOException(new IOException("synthetic: cannot delete"));
			}
			return invocation.callRealMethod();
		})) {
			process(worker(), job(null));
		}

		assertTrue("the archive that could not be deleted is left", Files.isRegularFile(archive));
		assertFalse("the extraction is deleted", Files.exists(finished.getParent().getParent()));
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

	/** The job's extraction directories in a directory, as the cleanup finds them. */
	private static List<Path> extractions(Path directory) throws IOException {
		List<Path> found = new ArrayList<>();
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "upload_" + JOB_ID + "_*")) {
			stream.forEach(found::add);
		}
		return found;
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
