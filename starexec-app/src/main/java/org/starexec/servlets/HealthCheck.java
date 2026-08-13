package org.starexec.servlets;

import org.starexec.data.database.Common;
import org.starexec.logger.StarLogger;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Liveness and readiness endpoints for container orchestrators.
 *
 * <p>These answer two different questions, and conflating them is the bug this
 * replaces: before this existed, the Dockerfile HEALTHCHECK and both Kubernetes
 * probes all fetched {@code /starexec/}, a static page Tomcat serves happily
 * while the database is unreachable. A pod with a dead database stayed in the
 * Service endpoints and was never restarted.
 *
 * <p><b>Liveness</b> asks only whether this process can still serve a request.
 * It deliberately never touches the database. A liveness probe that fails during
 * a database outage restarts the pod, and a restart cannot fix a database;
 * repeated across a sustained outage that is a restart storm, which destroys the
 * one process still able to report the problem.
 *
 * <p><b>Readiness</b> asks whether this instance can usefully serve traffic,
 * which for StarExec means reaching the database — nearly every page needs it.
 * Note that {@code charts/starexec/templates/deployment.yaml} refuses to render
 * with more than one replica, so failing readiness cannot shift traffic to a
 * healthy peer. Its value here is narrower but real: during a rollout it refuses
 * to cut over to a new pod that cannot reach the database.
 *
 * <p>Both responses are terse on purpose. {@code SessionFilter} exempts
 * {@code /public/}, so this path is unauthenticated and must not hand an
 * anonymous caller schema versions, hostnames, pool statistics or exception
 * text. The status code carries the signal; the body is a human courtesy.
 */
public class HealthCheck extends HttpServlet {
	private static final long serialVersionUID = 1L;
	private static final StarLogger log = StarLogger.getLogger(HealthCheck.class);

	/**
	 * Caps the readiness check below any sane probe timeout. The pool's own
	 * {@code maxWait} is 10s, which is longer than the probe budget, so the
	 * validation is bounded here rather than inherited from the pool.
	 */
	private static final int DB_PROBE_TIMEOUT_SECONDS = 2;

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response)
			throws IOException {
		final String path = request.getPathInfo() == null ? "" : request.getPathInfo();

		response.setContentType("text/plain; charset=UTF-8");
		// Probes must never be answered from a cache, or the orchestrator is
		// reading a claim about the past.
		response.setHeader("Cache-Control", "no-store");

		switch (path) {
			case "/liveness":
				respond(response, HttpServletResponse.SC_OK, "alive");
				break;
			case "/readiness":
				if (Common.isDatabaseReachable(DB_PROBE_TIMEOUT_SECONDS)) {
					respond(response, HttpServletResponse.SC_OK, "ready");
				} else {
					log.warn("doGet", "readiness probe failed: database not reachable");
					respond(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "not ready");
				}
				break;
			default:
				respond(response, HttpServletResponse.SC_NOT_FOUND, "not found");
		}
	}

	private static void respond(HttpServletResponse response, int status, String body)
			throws IOException {
		response.setStatus(status);
		response.getWriter().write(body + "\n");
	}
}
