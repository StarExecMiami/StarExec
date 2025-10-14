package org.starexec.servlets;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.tomcat.util.http.fileupload.servlet.ServletFileUpload;
import org.starexec.constants.R;
import org.starexec.data.database.Communities;
import org.starexec.data.database.Reports;
import org.starexec.data.database.Solvers;
import org.starexec.data.database.Users;
import org.starexec.data.security.JobSecurity;
import org.starexec.data.security.ValidatorStatusCode;
import org.starexec.data.to.*;
import org.starexec.data.to.Solver.ExecutableType;
import org.starexec.data.to.enums.ConfigXmlAttribute;
import org.starexec.data.to.enums.JobXmlType;
import org.starexec.data.to.tuples.ConfigAttrMapPair;
import org.starexec.data.to.tuples.UploadSolverResult;
import org.starexec.data.to.tuples.UploadSolverResult.UploadSolverStatus;
import org.starexec.jobs.JobManager;
import org.starexec.logger.StarLogger;
import org.starexec.util.*;
import org.starexec.util.SessionUtil;
import org.xml.sax.SAXException;

import javax.servlet.http.Cookie;
import javax.servlet.ServletException;
import javax.servlet.annotation.MultipartConfig;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import java.nio.file.Files;
import javax.xml.parsers.ParserConfigurationException;
/**
 * Allows for the uploading and handling of Solvers. Solvers can come in .zip, .tar, or .tar.gz format, and
 * configurations can be included in a top level "bin" directory. Each Solver is saved in a unique directory on the
 * filesystem.
 *
 * @author Skylar Stark
 */
// Explicit multipart limits to surface if large request is stuck before parsing (1GB caps temporary for debugging)
@MultipartConfig(fileSizeThreshold = 1024 * 1024, maxFileSize = 1024L * 1024L * 1024L, maxRequestSize = 1024L * 1024L * 1024L)
public class UploadSolver extends HttpServlet {

	private static final StarLogger log = StarLogger.getLogger(UploadSolver.class);
	// Some param constants to process the form
	private static final String SOLVER_DESC = "desc";
	private static final String SOLVER_DESC_FILE = "d";
	private static final String SOLVER_DOWNLOADABLE = "dlable";
	private static final String SPACE_ID = R.SPACE;
	private static final String UPLOAD_FILE = "f";
	private static final String SOLVER_NAME = "sn";
	private static final String UPLOAD_METHOD = "upMethod";
	private static final String DESC_METHOD = "descMethod";
	private static final String FILE_URL = "url";
	private static final String RUN_TEST_JOB = "runTestJob";
	private static final String SETTING_ID = "settingId";
	private static final String SOLVER_TYPE = "execType";
	private final DateFormat shortDate = new SimpleDateFormat(R.PATH_DATE_FORMAT);

	protected void doPost(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		final String method = "doPost";
		log.entry(method);
		int userId = SessionUtil.getUserId(request);
		try {
			log.info("doPost begins");

			if (abortIfUploadsFrozen(response)) {
				return;
			}

			final String rawCt = request.getHeader("Content-Type");
			boolean headerHeuristic = rawCt != null && rawCt.toLowerCase().startsWith("multipart/form-data");
			boolean skipCommons = Boolean.parseBoolean(System.getProperty("starexec.skip.commons.multipart","true"));
			Boolean commonsResult = null;
			if (!skipCommons) {
				try {
					commonsResult = ServletFileUpload.isMultipartContent(request);
				} catch (Throwable ex) {
					log.warn("Exception inside ServletFileUpload.isMultipartContent", ex);
				}
			}
			boolean isMultipart = headerHeuristic || Boolean.TRUE.equals(commonsResult);
			if (commonsResult != null && commonsResult != headerHeuristic) {
				log.warn("Mismatch commonsResult=" + commonsResult + " headerHeuristic=" + headerHeuristic);
			}
			if (isMultipart) {
				HashMap<String, Object> form = null;
				try {
					form = Util.parseMultipartRequest(request);
				} catch (Exception ex) {
					log.error("Exception while parsing multipart request", ex);
					response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Failed to parse multipart request: "+ex.getMessage());
					return;
				}

				if (form == null) {
					log.error("Form map is null after parsing");
					response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Malformed multipart request");
					return;
				}

				// Backward compatibility: some clients send 'type' instead of 'execType'
				if (!form.containsKey(SOLVER_TYPE) && form.containsKey("type")) {
					form.put(SOLVER_TYPE, form.get("type"));
				}
				ValidatorStatusCode status = this.isValidRequest(form, request);
				if (!status.isSuccess()) {
					response.addCookie(new Cookie(R.STATUS_MESSAGE_COOKIE, encodeCookieValue(status.getMessage())));
					response.sendError(HttpServletResponse.SC_BAD_REQUEST, status.getMessage());
					return;
				}

				int spaceId = Integer.parseInt((String) form.get(SPACE_ID));
				boolean runTestJob = Boolean.parseBoolean((String) form.get(RUN_TEST_JOB));

				// Parse the request as a solver
				UploadSolverResult result = handleSolver(userId, form);

				// Redirect based on success/failure
				switch (result.status) {
				case SUCCESS:
					if (result.isBuildJob) {
						int job_return = JobManager.addBuildJob(result.solverId, spaceId);
						if (job_return >= 0) {
							log.info("Build job created successfully. JobId: " + job_return);
						} else {
							log.error("Error in job creation for buildJob for solver: " + result.solverId);
						}
					}

					response.addCookie(new Cookie("New_ID", String.valueOf(result.solverId)));
					if (result.isBuildJob && !runTestJob) {
						response.sendRedirect(Util.docRoot("secure/details/solver.jsp?id=" + result.solverId +
																   "&buildmsg=Building Solver On Starexec"));
					} else if (!result.hadConfigs) {
						response.sendRedirect(Util.docRoot("secure/details/solver.jsp?id=" + result.solverId +
																   "&msg=No configurations for the new solver"));
					} else {
						if (runTestJob) {
							int settingsId = Communities.getDefaultSettings(spaceId).getId();
							if (form.containsKey(SETTING_ID)) {
								settingsId = Integer.parseInt((String) form.get(SETTING_ID));
							}

							int jobId = CreateJob.buildSolverTestJob(result.solverId, spaceId, userId, settingsId);
							if (result.isBuildJob && jobId > 0) {
								response.sendRedirect(Util.docRoot(
										"secure/details/solver.jsp?id=" + result.solverId +
												"&buildmsg=Building Solver On Starexec-- " +
												"test job will be run after build"));
							} else if (jobId > 0) {
								response.sendRedirect(Util.docRoot("secure/details/job.jsp?id=" + jobId));
							} else {
								response.sendRedirect(Util.docRoot(
										"secure/details/solver.jsp?id=" + result.solverId +
												"&msg=Internal error creating test job-- " +
												"solver uploaded successfully"));
							}
						} else if (result.optionalMessage.isPresent()) {
							String url = "secure/details/solver.jsp?id=" + result.solverId + "&msg=" +
									result.optionalMessage.get();
							response.sendRedirect(Util.docRoot(url));
						} else {
							response.sendRedirect(Util.docRoot("secure/details/solver.jsp?id=" + result.solverId));
						}
					}
					break;
				case EXTRACTING_ERROR:
					response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, result.status.message);
					break;
				default:
					response.sendError(HttpServletResponse.SC_BAD_REQUEST, result.status.message);
					break;
				}
			} else {
				response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Expected multipart/form-data");
			}
		} catch (Exception e) {
			log.error("Caught Exception in UploadSolver.doPost", e);
			response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
		}
	}

	/**
	 * Checks to see whether the given directory contains a solver build script in the top level
	 *
	 * @param dir The directory to look inside of
	 * @return True if the build script is there, and false otherwise
	 */
	public boolean containsBuildScript(File dir) {
		return new File(dir, R.SOLVER_BUILD_SCRIPT).exists();
	}

	private boolean containsRunOnUploadXml(File dir) {
		return new File(dir, R.UPLOAD_TEST_JOB_XML).exists();
	}

	/**
	 * This method is responsible for uploading a solver to the appropriate location and updating the database to
	 * reflect the solver's location.
	 *
	 * @param userId the user ID of the user making the upload request
	 * @param form the HashMap representation of the upload request
	 * @throws Exception
	 */
	public UploadSolverResult handleSolver(int userId, HashMap<String, Object> form) throws Exception {
		final String methodName = "handleSolver";
		log.info("handleSolver begins");

		File sandboxDir = null;

		try {
			sandboxDir = Util.getRandomSandboxDirectory();
			String upMethod = (String) form.get(UploadSolver.UPLOAD_METHOD);
			PartWrapper item = null;
			String name = null;
			URL url = null;
			Integer spaceId = Integer.parseInt((String) form.get(SPACE_ID));
			if (upMethod.equals("local")) {
				item = (PartWrapper) form.get(UploadSolver.UPLOAD_FILE);
			} else {
				try {
					url = URI.create((String) form.get(UploadSolver.FILE_URL)).toURL();
				} catch (Exception e) {
					log.error(e.getMessage(), e);
					return new UploadSolverResult(UploadSolverStatus.CANNOT_ACCESS_FILE, -1, false, false);
				}

				try {
					name = url.toString().substring(url.toString().lastIndexOf('/'));
				} catch (Exception e) {
					name = url.toString().replace('/', '-');
				}
			}

			//Set up a new solver with the submitted information
			Solver newSolver = new Solver();
			newSolver.setUserId(userId);
			newSolver.setName((String) form.get(UploadSolver.SOLVER_NAME));
			newSolver.setDownloadable((Boolean.parseBoolean((String) form.get(SOLVER_DOWNLOADABLE))));

			log.info("Handling upload of solver " + newSolver.getName());

			//Set up the unique directory to store the solver
			File uniqueDir = new File(R.getSolverPath(), "" + userId);
			uniqueDir = new File(uniqueDir, newSolver.getName());
			uniqueDir = new File(uniqueDir, "" + shortDate.format(new Date()));

			newSolver.setPath(uniqueDir.getAbsolutePath());

			uniqueDir.mkdirs();

			//Process the archive file and extract
			File archiveFile = null;
			if (upMethod.equals("local")) {
				if (item != null) {
					archiveFile = new File(uniqueDir, FilenameUtils.getName(item.getName()));
					new File(archiveFile.getParent()).mkdir();
					item.write(archiveFile);
				} else {
					throw new Exception("Upload file is null");
				}
			} else {
				archiveFile = new File(uniqueDir, name);
				new File(archiveFile.getParent()).mkdir();
				log.info(methodName, "downloading solver from url " + url);
				if (!Util.copyFileFromURLUsingProxy(url, archiveFile)) {
					throw new Exception("Unable to copy file from URL");
				}
			}
			long fileSize = ArchiveUtil.getArchiveSize(archiveFile.getAbsolutePath());

			User currentUser = Users.get(userId);
			long allowedBytes = currentUser.getDiskQuota();
			long usedBytes = currentUser.getDiskUsage();

			if (fileSize > allowedBytes - usedBytes) {
				archiveFile.delete();
				return new UploadSolverResult(UploadSolverStatus.EXCEED_QUOTA, -1, false, false);
			}

			FileUtils.copyFileToDirectory(archiveFile, sandboxDir);
			archiveFile.delete();
			archiveFile = new File(sandboxDir, archiveFile.getName());

			Util.sandboxChmodDirectoryDirect(sandboxDir);

			boolean extracted =
					ArchiveUtil.extractArchiveAsSandbox(archiveFile.getAbsolutePath(), sandboxDir.getAbsolutePath());

			Util.sandboxChmodDirectory(sandboxDir);

			if (!extracted || sandboxDir.listFiles().length == 0) {
				log.warn("Error extracting the new solver archive");
				FileUtils.deleteDirectory(sandboxDir);
				FileUtils.deleteDirectory(uniqueDir);
				FileUtils.deleteQuietly(archiveFile);
				return new UploadSolverResult(UploadSolverStatus.EXTRACTING_ERROR, -1, false, false);
			}
			boolean isBuildJob = false;
			if (containsBuildScript(sandboxDir)) {
				SolverBuildStatus status = new SolverBuildStatus();
				status.setCode(SolverBuildStatus.SolverBuildStatusCode.UNBUILT);
				newSolver.setBuildStatus(status);

				isBuildJob = true;
				uniqueDir = new File(newSolver.getPath() + "_src");
				newSolver.setPath(uniqueDir.getAbsolutePath());
				uniqueDir.mkdirs();
			} else {
				SolverBuildStatus status = new SolverBuildStatus();
				status.setCode(1);
				newSolver.setBuildStatus(status);
			}

			Util.sandboxChmodDirectory(sandboxDir);

			for (File f : sandboxDir.listFiles()) {
				if (f.isDirectory()) {
					try {
						FileUtils.copyDirectoryToDirectory(f, uniqueDir);
					} catch (FileNotFoundException e) {
						throw new FileNotFoundException(
								String.format("Check for broken symbolic links in your solver.%n%s", e.getMessage()));
					}
				} else {
					FileUtils.copyFileToDirectory(f, uniqueDir);
				}
			}

			String DescMethod = (String) form.get(UploadSolver.DESC_METHOD);
			switch (DescMethod) {
			case "text":
				newSolver.setDescription((String) form.get(UploadSolver.SOLVER_DESC));
				break;
			case "file":
				PartWrapper item_desc = (PartWrapper) form.get(UploadSolver.SOLVER_DESC_FILE);
				newSolver.setDescription(item_desc.getString());
				break;
			default:
				try {
					File descriptionFile = new File(uniqueDir, R.SOLVER_DESC_PATH);
					if (descriptionFile.exists()) {
						String description = Files.readString(descriptionFile.toPath());
						if (!Validator.isValidPrimDescription(description)) {
							return new UploadSolverResult(
									UploadSolverStatus.DESCRIPTION_MALFORMED, -1, false, isBuildJob);
						}
						newSolver.setDescription(description);
					}
				} catch (Exception e) {
					log.error(e.getMessage(), e);
				}
				break;
			}

			//Find configurations from the top-level "bin" directory
			for (Configuration c : Solvers.findConfigs(uniqueDir.getAbsolutePath())) {
				newSolver.addConfiguration(c);
			}

			boolean hadConfigs = !newSolver.getConfigurations().isEmpty();

			newSolver.setType(ExecutableType.valueOf(Integer.parseInt((String) form.get(SOLVER_TYPE))));
			int solverId = Solvers.add(newSolver, spaceId);

			UploadSolverStatus status = UploadSolverStatus.SUCCESS;

			UploadSolverResult result = new UploadSolverResult(status, solverId, hadConfigs, isBuildJob);
			if (containsRunOnUploadXml(sandboxDir)) {
				final File runOnUploadXml = new File(sandboxDir, R.UPLOAD_TEST_JOB_XML);
				JobUtil jobUtil = createTestJobFromXml(runOnUploadXml, userId, spaceId, solverId);
				if (!jobUtil.getJobCreationSuccess()) {
					String message = "Test job creation failed: " + jobUtil.getErrorMessage();
					result.optionalMessage = Optional.of(message);
				}
			}

			Reports.addToEventOccurrencesNotRelatedToQueue("solvers uploaded", 1);

			return result;
		} finally {
			try {
				if (sandboxDir != null) {
					FileUtils.deleteDirectory(sandboxDir);
				}
			} catch (Exception e) {
				log.error("Unable to delete temporary directory at " + (sandboxDir != null ? sandboxDir.getAbsolutePath() : "null"), e);
			}
		}
	}

	private JobUtil createTestJobFromXml(final File jobXml, final int userId, final int spaceId, final int newSolverId)
			throws SAXException, IOException, ParserConfigurationException {

		JobUtil jobUtil = new JobUtil();

		JobXmlType solverUploadType = JobXmlType.SOLVER_UPLOAD;
		List<Configuration> configs = Solvers.getConfigsForSolver(newSolverId);

		ConfigAttrMapPair configAttrMapPair = new ConfigAttrMapPair(ConfigXmlAttribute.NAME);
		for (Configuration c : configs) {
			configAttrMapPair.configNameToId.put(c.getName(), c.getId());
		}

		jobUtil.createJobsFromFile(jobXml, userId, spaceId, solverUploadType, configAttrMapPair);

		return jobUtil;
	}

	/**
	 * Sees if a given String -> Object HashMap is a valid Upload Solver request. Checks to see if it contains all the
	 * information needed and if the information is in the right format.
	 *
	 * @param form the HashMap representing the upload request.
	 * @return true iff the request is valid
	 */
	private ValidatorStatusCode isValidRequest(HashMap<String, Object> form, HttpServletRequest request) {
		// final String method = "isValidRequest";
		try {
			int userId = SessionUtil.getUserId(request);
			if (!form.containsKey(UPLOAD_METHOD) || !form.containsKey(UploadSolver.SOLVER_TYPE) ||
					(!form.containsKey(UploadSolver.UPLOAD_FILE) && form.get(UPLOAD_METHOD).equals("local")) ||
					!form.containsKey(DESC_METHOD) ||
					(!form.containsKey(SOLVER_DESC_FILE) && form.get(DESC_METHOD).equals("file"))) {
				return new ValidatorStatusCode(false, "Required parameters are missing from the request");
			}

			if (!Validator.isValidPosInteger((String) form.get(SPACE_ID))) {
				return new ValidatorStatusCode(false, "The given space ID is not a valid integer");
			}

			if (!Validator.isValidBool((String) form.get(SOLVER_DOWNLOADABLE))) {
				return new ValidatorStatusCode(false, "The 'downloadable' attribute needs to be a valid boolean");
			}

			if (!Validator.isValidPosInteger((String) form.get(SOLVER_TYPE))) {
				return new ValidatorStatusCode(false, "Executable Type needed to be sent as a valid integer");
			}
			ExecutableType type = ExecutableType.valueOf(Integer.parseInt((String) form.get(SOLVER_TYPE)));
			if (type == null) {
				return new ValidatorStatusCode(false, "Invalid executable type");
			}

			if (!Validator.isValidSolverName((String) form.get(UploadSolver.SOLVER_NAME))) {
				return new ValidatorStatusCode(
						false, "The given name is invalid-- please refer to the help files to see the proper format");
			}

			String DescMethod = (String) form.get(UploadSolver.DESC_METHOD);

			if (DescMethod.equals("file")) {
				PartWrapper item_desc = (PartWrapper) form.get(UploadSolver.SOLVER_DESC_FILE);
				if (!Validator.isValidPrimDescription(item_desc.getString())) {
					return new ValidatorStatusCode(
							false,
							"The given description is invalid-- please refer to the help files to see the proper " +
									"format"
					);
				}
			}

			if (!Validator.isValidPrimDescription((String) form.get(SOLVER_DESC))) {
				return new ValidatorStatusCode(
						false,
						"The given description is invalid-- please refer to the help files to see the proper format"
				);
			}

			String fileName = null;
			if (form.get(UploadSolver.UPLOAD_METHOD).equals("local")) {
				fileName = FilenameUtils.getName(((PartWrapper) form.get(UploadSolver.UPLOAD_FILE)).getName());
			} else {
				fileName = (String) form.get(UploadSolver.FILE_URL);
			}
			if (!Validator.isValidArchiveType(fileName)) {
				return new ValidatorStatusCode(false, "Archives need to have an extension of .zip, .tar, or .tgz");
			}

			int spaceId = Integer.parseInt((String) form.get(R.SPACE));
			Permission userPermissions = SessionUtil.getPermission(request, spaceId);
			if (userPermissions == null || !userPermissions.canAddSolver()) {
				return new ValidatorStatusCode(false, "You are not authorized to add solvers to this space");
			}

			if (!Validator.isValidBool((String) form.get(RUN_TEST_JOB))) {
				return new ValidatorStatusCode(false, "The 'run test job' attribute needs to be a valid boolean");
			}
			Boolean runTestJob = Boolean.parseBoolean((String) form.get(RUN_TEST_JOB));

			if (runTestJob) {
				int settingsId = Communities.getDefaultSettings(spaceId).getId();
				if (form.containsKey(SETTING_ID)) {
					if (!Validator.isValidPosInteger((String) form.get(SETTING_ID))) {
						return new ValidatorStatusCode(false, "The given setting ID is not a valid integer");
					}
					settingsId = Integer.parseInt((String) form.get(SETTING_ID));
				}

				ValidatorStatusCode testJobStatus =
						JobSecurity.canCreateQuickJobWithCommunityDefaults(userId, spaceId, settingsId);
				if (!testJobStatus.isSuccess()) {
					return testJobStatus;
				}
			}
			return new ValidatorStatusCode(true);
		} catch (Exception e) {
			log.warn(e.getMessage(), e);
		}
		return new ValidatorStatusCode(false, "Internal error uploading solver");
	}

	private boolean abortIfUploadsFrozen(HttpServletResponse response) throws IOException {
		boolean frozen = org.starexec.data.security.UploadSecurity.uploadsFrozen();
		if (frozen) {
			response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Uploading solvers is currently disabled");
			return true;
		}
		return false;
	}

	private String encodeCookieValue(String v) {
		if (v == null) { return ""; }
		try { return URLEncoder.encode(v, StandardCharsets.UTF_8.name()); } catch (Exception e) { return ""; }
	}
}
