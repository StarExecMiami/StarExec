package org.starexec.constants;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.apache.commons.io.IOUtils;
import org.starexec.logger.StarLogger;

public class PaginationQueries {
	private static final String GET_PAIRS_IN_SPACE_PATH = "/pagination/PairInJobSpacePagination.sql";
	public static String GET_PAIRS_IN_SPACE_QUERY = "";

	private static final String GET_PAIRS_IN_SPACE_HIERARCHY_PATH = "/pagination/PairInJobSpaceHierarchyPagination.sql";
	public static String GET_PAIRS_IN_SPACE_HIERARCHY_QUERY = "";

	private static final String GET_BENCHMARKS_IN_SPACE_PATH = "/pagination/BenchInSpacePagination.sql";
	public static String GET_BENCHMARKS_IN_SPACE_QUERY = "";

	private static final String GET_BENCHMARKS_BY_USER_PATH = "/pagination/BenchForUserPagination.sql";
	public static String GET_BENCHMARKS_BY_USER_QUERY = "";

	private static final String GET_JOBS_IN_SPACE_PATH = "/pagination/JobInSpacePagination.sql";
	public static String GET_JOBS_IN_SPACE_QUERY = "";

	private static final String GET_JOBS_BY_USER_PATH = "/pagination/JobForUserPagination.sql";
	public static String GET_JOBS_BY_USER_QUERY = "";

	private static final String GET_USERS_IN_SPACE_PATH = "/pagination/UserInSpacePagination.sql";
	public static String GET_USERS_IN_SPACE_QUERY = "";

	private static final String GET_SUBSPACES_IN_SPACE_PATH = "/pagination/SubspacesInSpacePagination.sql";
	public static String GET_SUBSPACES_IN_SPACE_QUERY = "";

	private static final String GET_SOLVERS_IN_SPACE_PATH = "/pagination/SolverInSpacePagination.sql";
	public static String GET_SOLVERS_IN_SPACE_QUERY = "";

	private static final String GET_SOLVERS_BY_USER_PATH = "/pagination/SolverForUserPagination.sql";
	public static String GET_SOLVERS_BY_USER_QUERY = "";

	private static final String GET_PAIRS_ENQUEUED_PATH = "/pagination/EnqueuedPairPagination.sql";
	public static String GET_PAIRS_ENQUEUED_QUERY = "";

	private static final String GET_USERS_ADMIN_PATH = "/pagination/UsersForAdmin.sql";
	public static String GET_USERS_ADMIN_QUERY = "";

	private static final String GET_UPLOADS_BY_USER_PATH = "/pagination/UploadForUserPagination.sql";
	public static String GET_UPLOADS_BY_USER_QUERY = "";

	private static final StarLogger log = StarLogger.getLogger(PaginationQueries.class);

	/**
	 * Reads in the queries stored in the config/pagination package
	 * 
	 * @throws IOException
	 */
	public static void loadPaginationQueries() throws IOException {
		GET_PAIRS_IN_SPACE_QUERY = loadQuery(GET_PAIRS_IN_SPACE_PATH);
		GET_BENCHMARKS_IN_SPACE_QUERY = loadQuery(GET_BENCHMARKS_IN_SPACE_PATH);
		GET_BENCHMARKS_BY_USER_QUERY = loadQuery(GET_BENCHMARKS_BY_USER_PATH);
		GET_JOBS_IN_SPACE_QUERY = loadQuery(GET_JOBS_IN_SPACE_PATH);
		GET_JOBS_BY_USER_QUERY = loadQuery(GET_JOBS_BY_USER_PATH);
		GET_USERS_IN_SPACE_QUERY = loadQuery(GET_USERS_IN_SPACE_PATH);
		GET_SUBSPACES_IN_SPACE_QUERY = loadQuery(GET_SUBSPACES_IN_SPACE_PATH);
		GET_SOLVERS_IN_SPACE_QUERY = loadQuery(GET_SOLVERS_IN_SPACE_PATH);
		GET_SOLVERS_BY_USER_QUERY = loadQuery(GET_SOLVERS_BY_USER_PATH);
		GET_PAIRS_IN_SPACE_HIERARCHY_QUERY = loadQuery(GET_PAIRS_IN_SPACE_HIERARCHY_PATH);
		GET_PAIRS_ENQUEUED_QUERY = loadQuery(GET_PAIRS_ENQUEUED_PATH);
		GET_USERS_ADMIN_QUERY = loadQuery(GET_USERS_ADMIN_PATH);
		GET_UPLOADS_BY_USER_QUERY = loadQuery(GET_UPLOADS_BY_USER_PATH);
	}

	private static String loadQuery(String relativePath) throws IOException {
		Path p = Paths.get(R.CONFIG_PATH, relativePath);
		String sql = null;

		if (Files.exists(p)) {
			log.info("Loading pagination query from filesystem: " + p.toAbsolutePath());
			sql = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
		} else {
			// Fallback to classpath
			String resourcePath = "/org/starexec/config" + relativePath;
			log.info("Filesystem query not found at " + p.toAbsolutePath() + ". Falling back to classpath: "
					+ resourcePath);
			try (InputStream is = PaginationQueries.class.getResourceAsStream(resourcePath)) {
				if (is == null) {
					log.error("Could not find pagination query in filesystem or classpath: " + relativePath);
					throw new IOException("Could not find pagination query: " + relativePath);
				}
				sql = IOUtils.toString(is, StandardCharsets.UTF_8);
			}
		}

		return normalizeQuery(sql);
	}

	private static String normalizeQuery(String raw) {
		if (raw == null)
			return null;
		// Replace MySQL-style CONCAT('%', :query, '%') usages (with optional whitespace)
		// with an explicit text concatenation that forces the :query parameter to
		// be treated as text in Postgres. Use a regex so variants like
		// CONCAT('%', :query , '%') are handled.
		return raw.replaceAll("CONCAT\\(\\s*'%'\\s*,\\s*:query\\s*,\\s*'%'\\s*\\)",
				"('%' || COALESCE(:query, '')::text || '%')");
	}
}
