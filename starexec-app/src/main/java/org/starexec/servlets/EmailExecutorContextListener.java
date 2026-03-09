package org.starexec.servlets;

import org.starexec.app.RESTServices;
import org.starexec.logger.StarLogger;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Ensures graceful shutdown of the dedicated email (SMTP) executor so that
 * queued emails have a chance to be sent before the JVM exits.
 */
public class EmailExecutorContextListener implements ServletContextListener {
	private static final StarLogger log = StarLogger.getLogger(EmailExecutorContextListener.class);
	private static final int AWAIT_TERMINATION_SECONDS = 10;

	@Override
	public void contextInitialized(ServletContextEvent sce) {
		// Executor is created by RESTServices; nothing to do here.
	}

	@Override
	public void contextDestroyed(ServletContextEvent sce) {
		ExecutorService emailExecutor = RESTServices.getEmailExecutor();
		if (emailExecutor == null) {
			return;
		}
		emailExecutor.shutdown();
		try {
			if (!emailExecutor.awaitTermination(AWAIT_TERMINATION_SECONDS, TimeUnit.SECONDS)) {
				emailExecutor.shutdownNow();
				log.warn("contextDestroyed", "Email executor did not finish in time; some queued emails may not have been sent.");
			}
		} catch (InterruptedException ie) {
			emailExecutor.shutdownNow();
			Thread.currentThread().interrupt();
		}
	}
}
