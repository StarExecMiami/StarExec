package org.starexec.backend;

import org.starexec.constants.R;
import org.starexec.logger.StarLogger;

/**
 * Centralized backend initialization and management.
 * Provides access to the backend instance configured in R class.
 */
public class BackendInitializer {
	private static final StarLogger log = StarLogger.getLogger(BackendInitializer.class);

	/**
	 * Initialize the backend by calling its initialize method.
	 * The backend instance is created by the R class static initializer.
	 */
	public static void initialize() {
		try {
			Backend backend = R.BACKEND;
			if (backend == null) {
				log.warn("No backend configured (R.BACKEND is null)");
				return;
			}

			String backendType = R.BACKEND_TYPE;
			log.info("Initializing backend of type: " + backendType);
			log.info("Backend implementation: " + backend.getClass().getSimpleName());

			// Initialize the backend with the configured backend root
			String backendRoot = R.BACKEND_ROOT;
			log.info("Backend root: " + backendRoot);
			backend.initialize(backendRoot);

			log.info("Backend initialization completed successfully");

		} catch (Exception e) {
			log.error("Failed to initialize backend", e);
		}
	}

	/**
	 * Get the initialized backend instance from R.BACKEND.
	 * @return The Backend instance, or null if not initialized
	 */
	public static Backend getBackend() {
		return R.BACKEND;
	}

	/**
	 * Shutdown the backend and release resources.
	 * Called during application shutdown via the ServletContextListener.
	 */
	public static void shutdown() {
		Backend backend = R.BACKEND;
		if (backend != null) {
			try {
				log.info("Shutting down backend...");
				backend.destroyIf();
				log.info("Backend shut down successfully");
			} catch (Exception e) {
				log.error("Error shutting down backend", e);
			}
		}
	}

	/**
	 * Check if a backend is configured and initialized.
	 * @return true if backend is initialized, false otherwise
	 */
	public static boolean isBackendInitialized() {
		return R.BACKEND != null;
	}
}
