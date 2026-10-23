package org.starexec.servlets;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.starexec.constants.R;
import org.starexec.data.database.*;
import org.starexec.data.security.UploadSecurity;
import org.starexec.data.security.ValidatorStatusCode;
import org.starexec.data.to.*;
import org.starexec.exceptions.StarExecException;
import org.starexec.logger.StarLogger;
import org.starexec.util.*;

import javax.servlet.ServletException;
import javax.servlet.annotation.MultipartConfig;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URI;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.nio.charset.StandardCharsets;

// SECURITY: Limit benchmark uploads to 5GB to prevent DoS attacks while allowing large benchmark sets
@MultipartConfig(
	fileSizeThreshold = 1024 * 1024,              // 1MB buffer in memory
	maxFileSize = 5L * 1024L * 1024L * 1024L,     // 5GB max per file
	maxRequestSize = 5L * 1024L * 1024L * 1024L   // 5GB max per request
)
public class UploadBenchmark extends HttpServlet {
	private static final StarLogger log = StarLogger.getLogger(UploadBenchmark.class);

	// The unique date stamped file name format (immutable, thread-safe)
	private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern(R.PATH_DATE_FORMAT);

	// Request attributes
	private static final String SPACE_ID = R.SPACE;
	private static final String UPLOAD_METHOD = "upMethod";
	private static final String BENCHMARK_FILE = "benchFile";
	private static final String BENCHMARK_TYPE = "benchType";
	private static final String BENCH_DOWNLOADABLE = "download";
	private static final String FILE_URL = "url";
	private static final String FILE_GIT = "git";
	private static final String FILE_LOC = "localOrURLOrGit";
	private static final String addSolver = "addSolver";
	private static final String addBench = "addBench";
	private static final String addUser = "addUser";
	private static final String addSpace = "addSpace";
	private static final String addJob = "addJob";
	private static final String removeSolver = "removeSolver";
	private static final String removeBench = "removeBench";
	private static final String removeUser = "removeUser";
	private static final String removeSpace = "removeSpace";
	private static final String removeJob = "removeJob";

	private static final String HAS_DEPENDENCIES = "dependency";
	private static final String LINKED = "linked";
	private static final String DEP_ROOT_SPACE_ID = "depRoot";

	protected void doGet(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
	}

	protected void doPost(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		try {
			if (UploadSecurity.uploadsFrozen()) {
				response.sendError(
					HttpServletResponse.SC_SERVICE_UNAVAILABLE,
					"Uploading benchmarks is currently disabled"
				);
				return;
			}

			// Extract data from the multipart request
			HashMap<String, Object> form = Util.parseMultipartRequest(request);
			ValidatorStatusCode status = isRequestValid(form, request);
			
			// If the request is valid to act on...
			if (status.isSuccess()) {
				Integer spaceId = Integer.parseInt((String) form.get(SPACE_ID));
				Integer userId = SessionUtil.getUserId(request);
				
				// Use new async pattern: save file and enqueue job
				long jobId = handleUploadRequestAsync(form, userId, spaceId);
				
				if (jobId > 0) {
					// Return 202 Accepted with job ID and initial status
					// This eliminates race condition - client doesn't need to poll immediately
					response.setStatus(HttpServletResponse.SC_ACCEPTED);
					response.setContentType("application/json");
					
					// Fetch the job status to return in initial response
					org.starexec.data.to.UploadJob job = 
						org.starexec.data.database.UploadJobQueue.getJob(jobId).orElse(null);
					
					String json;
					if (job != null) {
						json = String.format(
							"{\"jobId\": %d, \"status\": \"%s\", \"progressPercentage\": %d, \"totalFilesFound\": 0, \"totalFilesProcessed\": 0}",
							jobId,
							job.getStatus(),
							job.getProgressPercentage()
						);
					} else {
						// Fallback if job not found
						json = "{\"jobId\": " + jobId + ", \"status\": \"PENDING\", \"progressPercentage\": 0, \"totalFilesFound\": 0, \"totalFilesProcessed\": 0}";
					}
					response.getWriter().write(json);
				} else if (isDeletedUser(userId)) {
					// The user was deleted while the upload was under way (#275).
					response.sendError(
						HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
						"Could not find the user uploading these benchmarks"
					);
				} else {
					response.sendError(
						HttpServletResponse.SC_INTERNAL_SERVER_ERROR, 
						"Failed to enqueue upload job"
					);
				}
			} else {
				//attach the message as a cookie so we don't need to be parsing HTML in StarexecCommand
				response.addCookie(Util.createEncodedCookie(R.STATUS_MESSAGE_COOKIE, status.getMessage()));
				// Or else the request was invalid, send bad request error
				response.sendError(HttpServletResponse.SC_BAD_REQUEST, status.getMessage());
			}
		} catch (Exception e) {
			log.warn("doPost", e);
			response.sendError(
					HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "There was an error uploading the benchmarks.");
		}
	}

	/**
	 * Handles upload request using new async pattern.
	 * 1. Saves uploaded file to disk
	 * 2. Enqueues job for background processing
	 * 3. Returns immediately with job ID
	 *
	 * @param form The form data from the request
	 * @param userId The user ID
	 * @param spaceId The target space ID
	 * @return Job ID if successful, -1 on failure
	 */
	private long handleUploadRequestAsync(HashMap<String, Object> form, Integer userId, Integer spaceId) 
			throws Exception {
		final String method = "handleUploadRequestAsync";
		
		// Extract parameters
		final String uploadMethod = (String) form.get(UPLOAD_METHOD);
		final int typeId = Integer.parseInt((String) form.get(BENCHMARK_TYPE));
		final boolean downloadable = Boolean.parseBoolean((String) form.get(BENCH_DOWNLOADABLE));
		final boolean hasDependencies = Boolean.parseBoolean((String) form.get(HAS_DEPENDENCIES));
		final boolean linked = Boolean.parseBoolean((String) form.get(LINKED));
		final String depRootRaw = (String) form.get(DEP_ROOT_SPACE_ID);
		final int depRootSpaceId = Validator.isValidPosInteger(depRootRaw) ? 
				Integer.parseInt(depRootRaw) : spaceId;
		final Permission perm = this.extractPermissions(form);
		final String localOrUrlOrGit = (String) form.get(FILE_LOC);

		// Where the archive comes from: the file in this request, or an address to download it from.
		PartWrapper fileToUpload = null;
		URL url = null;
		String archiveName;
		if (localOrUrlOrGit.equals("local")) {
			fileToUpload = (PartWrapper) form.get(BENCHMARK_FILE);
			if (fileToUpload == null) {
				throw new Exception("No uploaded benchmark file provided for local upload");
			}
			archiveName = FilenameUtils.getName(fileToUpload.getName());
		} else if (localOrUrlOrGit.equals("URL")) {
			// Downloaded by the upload worker, not here (#139): an archive can take minutes to fetch,
			// which would hold this request, and the proxy in front of it, open. The job records the
			// address and the file the worker writes it to; isRequestValid has checked the address.
			url = new URI(((String) form.get(FILE_URL)).trim()).toURL();
			archiveName = Util.archiveNameFromUrl(url);
			if (archiveName == null) {
				throw new IllegalArgumentException(Util.ARCHIVE_NAME_REQUIRED);
			}
		} else {
			// Git uploads are the last step of #139 and still need a design decision.
			throw new UnsupportedOperationException("Git uploads not yet supported in async mode");
		}

		// Nothing else would ever read or delete the directory of an upload that is not queued, so
		// it is deleted again here unless the job is (#275). That includes the upload of a user
		// deleted while the archive arrived: the job's insert fails on its foreign key.
		File uniqueDir = getDirectoryForBenchmarkUpload(userId, null);
		long jobId = -1;
		try {
			File archiveFile = new File(uniqueDir, archiveName);
			if (fileToUpload != null) {
				fileToUpload.write(archiveFile);
				log.info(method, "Saved uploaded file to: " + archiveFile.getAbsolutePath());
			} else {
				log.info(method, "Upload by URL will be downloaded by the upload worker to: "
						+ archiveFile.getAbsolutePath());
			}
			String sourceUrl = url == null ? null : url.toString();

			// Enqueue job using the new immutable request builder.
			// This captures all parameters and the archive size for SJF scheduling.
			UploadJob.UploadJobRequest uploadRequest = new UploadJob.UploadJobRequest.Builder()
				.archivePath(archiveFile.getAbsolutePath())
				.userId(userId)
				.spaceId(spaceId)
				.uploadMethod(uploadMethod)
				.benchmarkTypeId(typeId)
				.downloadable(downloadable)
				// Jobs are claimed smallest first. An upload by URL is not downloaded yet, so its size is
				// unknown: it is queued at the most it can be, rather than at 0, which would put every such
				// job ahead of all others and let one user's downloads hold the worker.
				.archiveSize(sourceUrl == null ? archiveFile.length() : Util.ARCHIVE_DOWNLOAD_MAX_BYTES)
				.hasDependencies(hasDependencies)
				.depRootSpaceId(depRootSpaceId)
				.linked(linked)
				.sourceUrl(sourceUrl)
				// The default permissions of the spaces a "convert" upload creates, as the form chose them.
				.spacePermissions(perm)
				.build();

			jobId = UploadJobQueue.enqueueJob(uploadRequest);
			if (jobId > 0) {
				log.info(method, "Enqueued upload job " + jobId + " for user " + userId);
			} else {
				log.error(method, "Failed to enqueue upload job for user " + userId);
			}
			return jobId;
		} finally {
			if (jobId <= 0) {
				deleteDirectoryForBenchmarkUpload(userId, uniqueDir);
			}
		}
	}

	/**
	 * Creates a new, empty directory that benchmarks can be placed in:
	 * {@code <benchmark root>/<userId>/<time>}, or a directory named {@code name} inside that.
	 *
	 * <p>The time is the current one to the millisecond. A directory that exists already is never
	 * used again: two uploads, copies or adds that asked in the same millisecond shared it, so that
	 * each registered the other's files, and one deleting its own files after a failure deleted the
	 * other's (#275). The next millisecond is tried instead, which keeps the name a time that
	 * {@link UploadArtifactPathGuard#isUploadDirectoryName} accepts.
	 *
	 * @param userId The ID of the user who will own the new benchmarks
	 * @param name An optional name for a directory inside the new one; null for none
	 * @return the new directory: the one named {@code name} if a name was given
	 * @throws IOException if no new directory could be created
	 */
	public static File getDirectoryForBenchmarkUpload(int userId, String name) throws IOException {
		return getDirectoryForBenchmarkUpload(userId, name, LocalDateTime.now());
	}

	/** {@link #getDirectoryForBenchmarkUpload(int, String)} with the time to start from. */
	static File getDirectoryForBenchmarkUpload(int userId, String name, LocalDateTime start) throws IOException {
		Path userDir = Files.createDirectories(new File(R.getBenchmarkPath(), String.valueOf(userId)).toPath());
		for (int i = 0; i <= Util.MAX_DIRECTORY_SUFFIX; i++) {
			Path claimed = userDir.resolve(SHORT_DATE.format(start.plus(i, ChronoUnit.MILLIS)));
			try {
				Files.createDirectory(claimed);
			} catch (FileAlreadyExistsException e) {
				continue;
			}
			if (name == null) {
				return claimed.toFile();
			}
			try {
				return Files.createDirectory(Util.childWithin(claimed.toFile(), name).toPath()).toFile();
			} catch (IOException | RuntimeException e) {
				try {
					Files.deleteIfExists(claimed);
				} catch (IOException cleanup) {
					e.addSuppressed(cleanup);
				}
				throw e;
			}
		}
		throw new IOException("No new directory for benchmarks could be created in " + userDir);
	}

	/**
	 * Deletes a directory that {@link #getDirectoryForBenchmarkUpload} created, with everything in it,
	 * for an upload, copy or add that did not keep it: nothing else would ever delete it (#275).
	 * Never throws, so it cannot replace the exception that caused the cleanup.
	 *
	 * <p>It is the time-named directory that is deleted, so a named one inside it goes with it. If
	 * the user no longer exists, a user deletion ran meanwhile: it removed the user's whole
	 * directory, which the caller may have created again. That is then deleted too, while empty.
	 *
	 * @param userId the user the directory was created for
	 * @param dir the directory getDirectoryForBenchmarkUpload returned; null does nothing
	 */
	public static void deleteDirectoryForBenchmarkUpload(int userId, File dir) {
		final String method = "deleteDirectoryForBenchmarkUpload";
		if (dir == null) {
			return;
		}
		try {
			Path root = new File(R.getBenchmarkPath()).toPath().toAbsolutePath().normalize();
			Path claimed = claimedDirectory(root, userId, dir.toPath().toAbsolutePath().normalize());
			if (claimed == null) {
				log.warn(method, "Not deleting " + dir + ": it is not a directory created for user " + userId);
				return;
			}
			if (!Util.safeDeleteDirectory(claimed.toString())) {
				log.warn(method, "Could not delete " + claimed);
			}
			if (isDeletedUser(userId)) {
				Util.deleteEmptyParents(root, claimed);
			}
		} catch (RuntimeException e) {
			log.warn(method, "Could not clean up " + dir, e);
		}
	}

	/**
	 * The directory {@code <root>/<userId>/<time>} that dir is, or is inside, or null if dir is not
	 * in such a directory.
	 */
	static Path claimedDirectory(Path root, int userId, Path dir) {
		if (!dir.startsWith(root)) {
			return null;
		}
		Path relative = root.relativize(dir);
		if (relative.getNameCount() < 2
				|| !relative.getName(0).toString().equals(String.valueOf(userId))
				|| !UploadArtifactPathGuard.isUploadDirectoryName(relative.getName(1).toString())) {
			return null;
		}
		return root.resolve(relative.subpath(0, 2));
	}

	/**
	 * Whether the user has been deleted. Never throws, as it is asked in cleanup code: a failed
	 * lookup, whatever the cause, counts as not deleted, so that nothing is deleted on a guess.
	 */
	static boolean isDeletedUser(int userId) {
		try {
			return !Users.exists(userId);
		} catch (SQLException | RuntimeException e) {
			log.warn("isDeletedUser", "Could not check whether user " + userId + " exists", e);
			return false;
		}
	}

	/**
	 * Adds a single new benchmark to the database with contents given by a string
	 *
	 * @param benchText The contents of the new benchmark
	 * @param name The name to give the new benchmark
	 * @param userId The ID of the user creating this benchmark
	 * @param typeId The ID of the benchmark processor to use on this benchmark
	 * @param downloadable Whether the benchmark should be set as being "downloadable"
	 * @return The ID of the newly created benchmark
	 */
	public static Integer addBenchmarkFromText(
			String benchText, String name, int userId, int typeId, boolean downloadable
	) {
		File uniqueDir = null;
		Integer id = null;
		try {
			log.debug("trying to add benchmark with text = " + benchText + " and name = " + name);
			uniqueDir = getDirectoryForBenchmarkUpload(userId, null);
			FileUtils.writeStringToFile(new File(uniqueDir, name), benchText, StandardCharsets.UTF_8);
			List<Benchmark> bench =
					Benchmarks.extractSpacesAndBenchmarks(uniqueDir, typeId, userId, downloadable, null, null)
					          .getBenchmarksRecursively();
			//add the benchmark to the database, but don't put it in any spaces
			id = Benchmarks.processAndAdd(bench, null, 1, false, null).get(0);
			return id;
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		} finally {
			// No benchmark row refers to the file of one that was not added (#275).
			if (id == null || id <= 0) {
				deleteDirectoryForBenchmarkUpload(userId, uniqueDir);
			}
		}

		return null;
	}

	/**
	 * Adds a single new benchmark to the database with contents given by a File
	 *
	 * @param benchFile The file containing the new benchmark, name of file will be name of benchmark
	 * @param userId The ID of the user creating this benchmark
	 * @param typeId The ID of the benchmark processor to use on this benchmark
	 * @param downloadable Whether the benchmark should be set as being "downloadable"
	 * @return The ID of the newly created benchmark
	 */
	public static Integer addBenchmarkFromFile(File benchFile, int userId, int typeId, boolean downloadable) {
		File uniqueDir = null;
		Integer id = null;
		try {
			uniqueDir = getDirectoryForBenchmarkUpload(userId, null);
			FileUtils.copyFileToDirectory(benchFile, uniqueDir);
			String[] filesInUniqueDir = uniqueDir.list();
			log.debug("Files in uniqueDir: ");
			for (String s : filesInUniqueDir) {
				log.debug("    " + s);
			}

			List<Benchmark> bench =
					Benchmarks.extractSpacesAndBenchmarks(uniqueDir, typeId, userId, downloadable, null, null)
					          .getBenchmarksRecursively();
			//add the benchmark to the database, but don't put it in any spaces
			id = Benchmarks.processAndAdd(bench, null, 1, false, null).get(0);
			return id;
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		} finally {
			// No benchmark row refers to the file of one that was not added (#275).
			if (id == null || id <= 0) {
				deleteDirectoryForBenchmarkUpload(userId, uniqueDir);
			}
		}
		return null;
	}

	/**
	 * Adds a set of benchmarks to the database by extracting the given archive and finding the benchmarks inside of it
	 *
	 * @param archiveFile The archive to look into
	 * @param userId The user who will own all of the new benchmarks
	 * @param spaceId The ID of the root space for this upload (what space did the user click "upload benchmarks" in?)
	 * @param typeId The ID of the benchmark processor that will be applied to the new benchmarks,
	 * @param downloadable Whether each of the benchmarks should be flagged as downloadable
	 * @param perm Permissions object representing the permissions for any new spaces created as a result of this
	 * upload
	 * @param uploadMethod "convert" or "dump", depending on whether to make a hierarchy or just put all benchmarks in
	 * the root space
	 * @param statusId The ID of the UploadStatus object for tracking this upload
	 * @param hasDependencies Whether the benchmarks have dependencies
	 * @param linked
	 * @param depRootSpaceId The root space for dependencies for these benchmarks
         * @return list of ids of benchmarks added, if uploadMethod is "dump"; otherwise empty list
	 * @throws Exception
	 */
	public static List<Integer> addBenchmarksFromArchive(
			File archiveFile, int userId, int spaceId, int typeId, boolean downloadable, Permission perm,
			String uploadMethod, int statusId, boolean hasDependencies, boolean linked, Integer depRootSpaceId
	) throws IOException, StarExecException {

		// Create a unique path the zip file will be extracted to
		final File uniqueDir = getDirectoryForBenchmarkUpload(userId, null);

		// Create the zip file object-to-be
		long fileSize = ArchiveUtil.getArchiveSize(archiveFile.getAbsolutePath());

		User currentUser = Users.get(userId);
		long allowedBytes = currentUser.getDiskQuota();
		long usedBytes = currentUser.getDiskUsage();

		if (fileSize > allowedBytes - usedBytes) {
			archiveFile.delete();
			Uploads.setBenchmarkErrorMessage(statusId,
			                                 "The benchmark upload is too large to fit in your disk quota. The " +
					                                 "uncompressed" +
					                                 " size of the archive is approximately " + fileSize +
					                                 " bytes, but you have only " + (allowedBytes - usedBytes) +
					                                 " bytes remaining.");
			throw new StarExecException("File too large to fit in user's disk quota");
		}

		// Copy the benchmark zip to the server from the client

		List<Integer> ids = new ArrayList<Integer>();
		log.info("upload complete - now extracting");
		Uploads.benchmarkFileUploadComplete(statusId);
		// Extract the downloaded benchmark zip file
		if (!ArchiveUtil.extractArchive(archiveFile.getAbsolutePath(), uniqueDir.getAbsolutePath())) {
			String message = "StarExec has failed to extract your uploaded file.";
			Uploads.setBenchmarkErrorMessage(statusId, message);
			log.error(message + " - status id = " + statusId + ", filepath = " + archiveFile.getAbsolutePath());
			return ids;
		}
		log.info("Extraction Complete");
		//update upload status
		Uploads.fileExtractComplete(statusId);

		// Count total benchmarks immediately to give user feedback
		int totalBenchmarks = Benchmarks.countBenchmarksInDirectory(uniqueDir);
		Uploads.setTotalBenchmarks(statusId, totalBenchmarks);


		log.debug("has dependencies = " + hasDependencies);
		log.debug("linked = " + linked);
		log.debug("depRootSpaceIds = " + depRootSpaceId);

		log.info("about to add benchmarks to space " + spaceId + "for user " + userId);
		
		//update Status
		Uploads.processingBegun(statusId);

		if (uploadMethod.equals("convert")) {
			log.debug("convert");

			//first we test to see if any names conflict
			ValidatorStatusCode status = doSpaceNamesConflict(uniqueDir, spaceId);
			if (!status.isSuccess()) {
				Uploads.setBenchmarkErrorMessage(statusId, status.getMessage());
				return ids;
			}

			// Calculate totals first so progress bar works

			Spaces.traverseAndAddBenchmarks(uniqueDir, spaceId, userId, typeId, downloadable, perm, statusId,
					hasDependencies, depRootSpaceId, linked);
		} else if (uploadMethod.equals("dump")) {
			Space result = Benchmarks.extractSpacesAndBenchmarks(uniqueDir, typeId, userId, downloadable, perm, statusId);
			if (result == null) {
				String message = "StarExec has failed to extract the spaces and benchmarks from the files.";
				Uploads.setBenchmarkErrorMessage(statusId, message);
				log.error(message + " - status id = " + statusId);
				return ids;
			}
			result.setId(spaceId);

			List<Benchmark> benchmarks = result.getBenchmarksRecursively();

			ids.addAll(Benchmarks.processAndAdd(benchmarks, spaceId, depRootSpaceId, linked, statusId,
					hasDependencies
			));
		}

		log.info("Handle upload method complete in " + spaceId + "for user " + userId);
                return ids;
	}


		/**
		 * Adds a set of benchmarks to the database by extracting the given directory and finding the benchmarks inside of it
		 *
		 * @param gitSpace The directory of the git clone
		 * @param userId The user who will own all of the new benchmarks
		 * @param spaceId The ID of the root space for this upload (what space did the user click "upload benchmarks" in?)
		 * @param typeId The ID of the benchmark processor that will be applied to the new benchmarks,
		 * @param downloadable Whether each of the benchmarks should be flagged as downloadable
		 * @param perm Permissions object representing the permissions for any new spaces created as a result of this
		 * upload
		 * @param uploadMethod "convert" or "dump", depending on whether to make a hierarchy or just put all benchmarks in
		 * the root space
		 * @param statusId The ID of the UploadStatus object for tracking this upload
		 * @param hasDependencies Whether the benchmarks have dependencies
		 * @param linked
		 * @param depRootSpaceId The root space for dependencies for these benchmarks
		 * @throws Exception
		 */
		public static void addBenchmarksGit(File gitSpace, int userId, int spaceId, int typeId, boolean downloadable, Permission perm,
		String uploadMethod, int statusId, boolean hasDependencies, boolean linked, Integer depRootSpaceId)
		throws IOException, StarExecException{

			//get the approximate files size, larger than actual beacause the .git directory is present still
			long fileSize = FileUtils.sizeOf(gitSpace);
			log.debug("size of file: " + fileSize);
			User currentUser = Users.get(userId);
			long allowedBytes = currentUser.getDiskQuota();
			long usedBytes = currentUser.getDiskUsage();

			if (fileSize > allowedBytes - usedBytes) {
				FileUtils.deleteDirectory(gitSpace);
				Uploads.setBenchmarkErrorMessage(statusId,
				                                 "The benchmark upload is too large to fit in your disk quota. The " +
						                                 "uncompressed" +
						                                 " size of the archive is approximately " + fileSize +
						                                 " bytes, but you have only " + (allowedBytes - usedBytes) +
						                                 " bytes remaining.");
				throw new StarExecException("File too large to fit in user's disk quota");
			}

			log.info("upload complete - now extracting");
			Uploads.benchmarkFileUploadComplete(statusId);
			log.info("Extraction Complete");
			//update upload status
			//This was apart of the orignial archive process so I left the message update
			Uploads.fileExtractComplete(statusId);

			// Count total benchmarks immediately to give user feedback
			int totalBenchmarks = Benchmarks.countBenchmarksInDirectory(gitSpace);
			Uploads.setTotalBenchmarks(statusId, totalBenchmarks);


			log.debug("has dependencies = " + hasDependencies);
			log.debug("linked = " + linked);
			log.debug("depRootSpaceIds = " + depRootSpaceId);

			log.info("about to add benchmarks to space " + spaceId + " for user " + userId);
			Space result = Benchmarks.extractSpacesAndBenchmarks(gitSpace, typeId, userId, downloadable, perm, statusId);
			if (result == null) {
				String message = "StarExec has failed to extract the spaces and benchmarks from the files.";
				Uploads.setBenchmarkErrorMessage(statusId, message);
				log.error(message + " - status id = " + statusId);
				return;
			}
			result.setId(spaceId);

			//update Status
			Uploads.processingBegun(statusId);

			if (uploadMethod.equals("convert")) {
				log.debug("convert");

				//first we test to see if any names conflict
				ValidatorStatusCode status = doSpaceNamesConflict(gitSpace, spaceId);
				if (!status.isSuccess()) {
					Uploads.setBenchmarkErrorMessage(statusId, status.getMessage());
					return;
				}

				Spaces.addWithBenchmarks(result, userId, depRootSpaceId, linked, statusId,
		                                         hasDependencies);
			} else if (uploadMethod.equals("dump")) {
				List<Benchmark> benchmarks = result.getBenchmarksRecursively();

				Benchmarks.processAndAdd(benchmarks, spaceId, depRootSpaceId, linked, statusId,
				                         hasDependencies
				);
			}
			log.info("Handle upload method complete in " + spaceId + "for user " + userId);
		}


	private void handleUploadRequest(HashMap<String, Object> form, Integer uId, Integer sId) throws Exception {
		//First extract all data from request
		final int userId = uId;

		final int spaceId = Integer.parseInt((String) form.get(SPACE_ID));
		final String uploadMethod = (String) form.get(UPLOAD_METHOD);
		final int typeId = Integer.parseInt((String) form.get(BENCHMARK_TYPE));
		final boolean downloadable = Boolean.parseBoolean((String) form.get(BENCH_DOWNLOADABLE));
		final boolean hasDependencies = Boolean.parseBoolean((String) form.get(HAS_DEPENDENCIES));
		final boolean linked = Boolean.parseBoolean((String) form.get(LINKED));
		final String depRootRaw = (String) form.get(DEP_ROOT_SPACE_ID);
		final int depRootSpaceId;
		if (Validator.isValidPosInteger(depRootRaw)) {
			depRootSpaceId = Integer.parseInt(depRootRaw);
		} else {
			log.debug("handleUploadRequest",
			          "depRoot missing or invalid; defaulting dependency root to space " + spaceId);
			depRootSpaceId = spaceId;
		}
		final Permission perm = this.extractPermissions(form);
		final Integer statusId = sId;
		final String localOrUrlOrGit = (String) form.get(FILE_LOC);

		URL tempURL = null;
		String tempName = null;
		PartWrapper tempFileToUpload = null;
		if (localOrUrlOrGit.equals("URL")) {
			tempURL = new URI((String) form.get(FILE_URL)).toURL();
			tempName = Util.archiveNameFromUrl(tempURL);
			if (tempName == null) {
				throw new IllegalArgumentException(Util.ARCHIVE_NAME_REQUIRED);
			}
		} else {
			tempFileToUpload = ((PartWrapper) form.get(BENCHMARK_FILE));
		}
		String tempGitUrl = null;
		if (localOrUrlOrGit.equals("Git")) {
			tempURL = new URI((String) form.get(FILE_GIT)).toURL();
			tempGitUrl = ((String) form.get(FILE_GIT)).trim();
			log.debug("URL is : " + ((String) form.get(FILE_GIT)));
			try {
				tempName = tempURL.toString().substring(tempURL.toString().lastIndexOf('/'));
			} catch (Exception e) {
				tempName = tempURL.toString().replace('/', '-');
			}
		} else {
			tempFileToUpload = ((PartWrapper) form.get(BENCHMARK_FILE));
		}


		final String name = tempName;
		final URL url = tempURL;
		final PartWrapper fileToUpload = tempFileToUpload;
		final String gitUrl = tempGitUrl;

		log.debug("upload status id is " + statusId);

		//It will delay the redirect until this method is finished which is why a new thread is used


		// Create a unique path the zip file will be extracted to
		File uniqueDirBuilder = new File(R.getBenchmarkPath(), String.valueOf(userId));
		Calendar calendar = Calendar.getInstance();

		uniqueDirBuilder = new File(uniqueDirBuilder, String.valueOf(calendar.get(Calendar.YEAR)));
		uniqueDirBuilder = new File(uniqueDirBuilder, String.valueOf(calendar.get(Calendar.MONTH) + 1));
		uniqueDirBuilder = new File(uniqueDirBuilder, String.valueOf(calendar.get(Calendar.DAY_OF_MONTH)));
		uniqueDirBuilder = new File(uniqueDirBuilder, String.valueOf(calendar.get(Calendar.HOUR_OF_DAY)));
		uniqueDirBuilder = new File(uniqueDirBuilder, String.valueOf(calendar.get(Calendar.MINUTE)));
		// the random string is to ensure that this directory is unique. It would not be otherwise if the
		// user uploads two benchmark directories in the same minute, which can easily happen using StarexecCommand
		final File uniqueDir = new File(uniqueDirBuilder, Util.getRandomAlphaString(20));
		// Create the paths on the filesystem
                if (uniqueDir.mkdirs()) {
                    log.info("Directory has been created");
                }
                else {
                    log.info("Directory has NOT been created");
                }

		log.info("Handling upload request for user " + userId + " in space " + spaceId);

		File archive = null;
		if (localOrUrlOrGit.equals("local")) {
			if (fileToUpload == null) {
				throw new Exception("No uploaded benchmark file provided for local upload");
			}
			archive = new File(uniqueDir, FilenameUtils.getName(fileToUpload.getName()));
			fileToUpload.write(archive);
		}

		final File archiveFile = archive;


		if (localOrUrlOrGit.equals("Git")){
			Util.threadPoolExecute(() -> {
				try {
					String gitSpaceString = uniqueDir.getAbsolutePath();
					log.debug("String is: "+gitSpaceString);
					log.debug("Before addBenchmarksGit: "+ uniqueDir.getAbsolutePath());

					String[] gitClonecmd = new String[4];
					gitClonecmd[0] = "git";
					gitClonecmd[1] = "clone";
					gitClonecmd[2] = gitUrl;
					gitClonecmd[3] = gitSpaceString;
					log.debug("gitclonecmd: " + gitClonecmd[0] + " " + gitClonecmd[1] + " " + gitClonecmd[2]+" " +gitClonecmd[3]);
					Util.executeCommand(gitClonecmd);

					String[] gitSubmodulecmd = new String[5];
					gitSubmodulecmd[0] = "git";
					gitSubmodulecmd[1] = "submodule";
					gitSubmodulecmd[2] = "update";
					gitSubmodulecmd[3] = "--init";
					gitSubmodulecmd[4] = "--recursive";

					log.debug("gitSubmodulecmd: " + gitSubmodulecmd[0] + " " + gitSubmodulecmd[1] + " " + gitSubmodulecmd[2]+" "
										+gitSubmodulecmd[3]+ " " + gitSubmodulecmd[4]);
					Util.executeCommand(gitSubmodulecmd,null, uniqueDir);

					addBenchmarksGit(uniqueDir, userId, spaceId, typeId, downloadable, perm, uploadMethod,
											 statusId, hasDependencies, linked, depRootSpaceId
					);

					BenchmarkUploadStatus status = Uploads.getBenchmarkStatus(statusId);

					if (status.isFileUploadComplete()) {
						// if the benchmarks archive was successfully uploaded record that in the weekly reports table
						Reports.addToEventOccurrencesNotRelatedToQueue("benchmark archives uploaded", 1);
						// Record the total number of benchmarks uploaded in the weekly reports data table
						int totalBenchmarksUploaded = status.getTotalBenchmarks();
						Reports.addToEventOccurrencesNotRelatedToQueue("benchmarks uploaded", totalBenchmarksUploaded);
					}
				} catch (Exception e) {
					String fileName = (archiveFile != null) ? archiveFile.getName() : (uniqueDir != null ? uniqueDir.getName() : "unknown");
					String msg = "userId:      " + userId
						+ "\nspaceId:     " + spaceId
						+ "\narchiveFile: " + fileName
					;
					log.error("handleUploadRequest", msg, e);
				} finally {
					Uploads.benchmarkEverythingComplete(statusId);
				}
			});
		}
		else{
			Util.threadPoolExecute(() -> {
				try {
					File archiveToUse = archiveFile;
					if (localOrUrlOrGit.equals("URL")) {
						archiveToUse = new File(uniqueDir, name);
						if (!Util.copyFileFromURLUsingProxy(url, archiveToUse)) {
							throw new Exception("Unable to copy file from URL");
						}
					}

					addBenchmarksFromArchive(archiveToUse, userId, spaceId, typeId, downloadable, perm, uploadMethod,
											 statusId, hasDependencies, linked, depRootSpaceId
					);

					BenchmarkUploadStatus status = Uploads.getBenchmarkStatus(statusId);

					if (status.isFileUploadComplete()) {
						// if the benchmarks archive was successfully uploaded record that in the weekly reports table
						Reports.addToEventOccurrencesNotRelatedToQueue("benchmark archives uploaded", 1);
						// Record the total number of benchmarks uploaded in the weekly reports data table
						int totalBenchmarksUploaded = status.getTotalBenchmarks();
						Reports.addToEventOccurrencesNotRelatedToQueue("benchmarks uploaded", totalBenchmarksUploaded);
					}
				} catch (Exception e) {
					String fileName = (archiveFile != null) ? archiveFile.getName() : (uniqueDir != null ? uniqueDir.getName() : "unknown");
					String msg = "userId:      " + userId
						+ "\nspaceId:     " + spaceId
						+ "\narchiveFile: " + fileName
					;
					log.error("handleUploadRequest", msg, e);
				} finally {
					Uploads.benchmarkEverythingComplete(statusId);
				}
			});
		}
	}

	/**
	 * Extracts the permissions object contained in the given form
	 *
	 * @param form The form to extract permissions from
	 * @return A permission object build from the fields contained in the form
	 */
	private Permission extractPermissions(HashMap<String, Object> form) {
		Permission p = new Permission();
		p.setAddBenchmark(granted(form, addBench));
		p.setAddSolver(granted(form, addSolver));
		p.setAddSpace(granted(form, addSpace));
		p.setAddUser(granted(form, addUser));
		p.setAddJob(granted(form, addJob));
		p.setRemoveBench(granted(form, removeBench));
		p.setRemoveSolver(granted(form, removeSolver));
		p.setRemoveSpace(granted(form, removeSpace));
		p.setRemoveUser(granted(form, removeUser));
		p.setRemoveJob(granted(form, removeJob));

		return p;
	}

	/**
	 * Whether the form grants a permission. A checkbox is sent only when checked, as "on";
	 * StarexecCommand sends every permission, as "true" or "false", so presence alone is not
	 * enough: it turned every permission StarexecCommand withheld into one it granted.
	 */
	static boolean granted(HashMap<String, Object> form, String name) {
		Object value = form.get(name);
		return value != null && !"false".equalsIgnoreCase(value.toString().trim());
	}

	/**
	 * Validates a benchmark upload request to determine if it can be acted on or not.
	 *
	 * @param form A list of form items contained in the request
	 * @return True if the request is valid to act on, false otherwise
	 * @author ??? - modified by Ben
	 */
	private ValidatorStatusCode isRequestValid(HashMap<String, Object> form, HttpServletRequest request) {
		final String method = "isRequestValid";
		try {

			// Check for required fields and provide specific error messages for missing values
			if (form.get(BENCHMARK_TYPE) == null || ((String) form.get(BENCHMARK_TYPE)).isEmpty()) {
				return new ValidatorStatusCode(false, "Benchmark processor ID is required");
			}
			if (!Validator.isValidPosInteger((String) form.get(BENCHMARK_TYPE))) {
				return new ValidatorStatusCode(false, "The given benchmark processor ID is not a valid integer");
			}

			if (form.get(SPACE_ID) == null || ((String) form.get(SPACE_ID)).isEmpty()) {
				return new ValidatorStatusCode(false, "Space ID is required");
			}
			if (!Validator.isValidPosInteger((String) form.get(SPACE_ID))) {
				return new ValidatorStatusCode(false, "The given space ID is not a valid integer");
			}

			if (form.get(BENCH_DOWNLOADABLE) == null || ((String) form.get(BENCH_DOWNLOADABLE)).isEmpty()) {
				return new ValidatorStatusCode(false, "The 'bench downloadable' option is required");
			}
			if (!Validator.isValidBool((String) form.get(BENCH_DOWNLOADABLE))) {
				return new ValidatorStatusCode(false, "The 'bench downloadable' option needs to be a valid boolean");
			}

			// Make sure we have a valid upload method
			String uploadMethod = ((String) form.get(UPLOAD_METHOD));
			if (uploadMethod == null || uploadMethod.isEmpty()) {
				return new ValidatorStatusCode(false, "Upload method is required");
			}
			if (!(uploadMethod.equals("convert") || uploadMethod.equals("dump"))) {
				return new ValidatorStatusCode(false, "The upload method needs to be either 'convert' or 'dump'");
			}

			// Check file location selection
			String fileLoc = (String) form.get(FILE_LOC);
			if (fileLoc == null || fileLoc.isEmpty()) {
				return new ValidatorStatusCode(false, "Please select a file source (local, URL, or Git)");
			}

			String fileName = null;
			// Last test, return true when we find a valid file extension
			if (fileLoc.equals("local")) {
				fileName = ((PartWrapper) form.get(BENCHMARK_FILE)).getName();
				if (!Validator.isValidArchiveType(fileName)) {
					return new ValidatorStatusCode(false, "Uploaded archives need to be either .zip, .tar, or .tgz");
				}
			}
			else if (fileLoc.equals("URL")) {
				fileName = (String) form.get(FILE_URL);
				if (!Validator.isValidArchiveType(fileName)) {
					return new ValidatorStatusCode(false, "Uploaded archives need to be either .zip, .tar, or .tgz");
				}
				String urlProblem = archiveUrlProblem(fileName);
				if (urlProblem != null) {
					return new ValidatorStatusCode(false, urlProblem);
				}
			}
			else {
				log.debug("in else");
				fileName = (String) form.get(FILE_GIT);
				log.debug("fileName: "+ fileName);
				if (!Validator.isValidGitType(fileName)) {
					return new ValidatorStatusCode(false, "Uploaded Git URLs need to be .git");
				}
			}

			// Validate space parameter for permission check
			String spaceParam = (String) form.get(R.SPACE);
			if (spaceParam == null || spaceParam.isEmpty()) {
				return new ValidatorStatusCode(false, "Space parameter is missing");
			}
			Permission perm = SessionUtil.getPermission(request, Integer.parseInt(spaceParam));

			log.trace(method, "perm=" + perm);
			log.trace(method, "uploadMethod=" + uploadMethod);

			if (perm == null || (!perm.canAddBenchmark() && uploadMethod.equals("dump"))) {
				// They don't have permissions, send forbidden error
				return new ValidatorStatusCode(false, "You do not have permission to upload benchmarks to this space");
			} else if (uploadMethod.equals("convert") && !(perm.canAddBenchmark() && perm.canAddSpace())) {
				return new ValidatorStatusCode(
						false, "You do not have permission to upload benchmarks and subspaces to this space");
			}

			// The dependency root is a second space this upload reads from: includes resolve against
			// the benchmarks in it. Only the target space was checked above. When the root is absent
			// it defaults to the target space, which was.
			if (Boolean.parseBoolean((String) form.get(HAS_DEPENDENCIES))) {
				String depRoot = (String) form.get(DEP_ROOT_SPACE_ID);
				if (Validator.isValidPosInteger(depRoot)
						&& !Permissions.canUserSeeSpace(Integer.parseInt(depRoot), SessionUtil.getUserId(request))) {
					return new ValidatorStatusCode(false, "You do not have permission to see the dependency root space");
				}
			}

			return new ValidatorStatusCode(true);
		} catch (Exception e) {
			log.warn(e.getMessage(), e);
		}

		// Return false control flow is broken and ends up here
		return new ValidatorStatusCode(false, "Internal error uploading benchmarks");
	}

	/**
	 * Checks to see if any of the spaces that will be created by the given upload directory conflict with existing
	 * names
	 *
	 * @param uniqueDir
	 * @return A ValidatorStatusCode set to True if there is NO conflict and set to false with a message if a conflict
	 * exists
	 */

	private static ValidatorStatusCode doSpaceNamesConflict(File uniqueDir, int parentSpaceId) {
		try {
			List<Space> subspaces = Spaces.getSubSpaces(parentSpaceId);
			HashSet<String> subspaceNames = new HashSet<>();
			for (Space s : subspaces) {
				subspaceNames.add(s.getName());
			}
			for (File f : uniqueDir.listFiles()) {
				// If it's a sub-directory and as such a subspace
				if (f.isDirectory()) {
					String curName = f.getName();
					if (subspaceNames.contains(curName)) {
						return new ValidatorStatusCode(false,
						                               "Creating spaces for your benchmarks would lead to having two subspaces with the name " +
								                               curName); // found a conflict
					}
					subspaceNames.add(curName);
				}
			}

			return new ValidatorStatusCode(true);
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		}
		return new ValidatorStatusCode(false, "There was an internal error uploading your benchmarks");
	}

	/**
	 * Why an archive URL cannot be downloaded, or null when it can be tried (#139).
	 *
	 * <p>Checked when the upload is submitted, so a bad address is refused at once with a message
	 * rather than becoming a failed job. Whether the host is reachable, and allowed, is left to the
	 * download itself, which applies one policy to every redirect.
	 */
	static String archiveUrlProblem(String raw) {
		URL url;
		try {
			url = new URI(raw == null ? "" : raw.trim()).toURL();
		} catch (Exception e) {
			return "The archive URL is not a valid URL";
		}
		String scheme = url.getProtocol();
		if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
			return "Archive URLs must use http or https";
		}
		// The download never sends these as credentials, so all they could do is sit in the
		// upload_jobs row and the logs.
		if (url.getUserInfo() != null) {
			return "Archive URLs must not contain a user name or password";
		}
		if (Util.archiveNameFromUrl(url) == null) {
			return Util.ARCHIVE_NAME_REQUIRED;
		}
		return null;
	}
}
