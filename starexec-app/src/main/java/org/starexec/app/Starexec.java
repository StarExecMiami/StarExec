package org.starexec.app;

import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

import org.starexec.backend.Backend;
import org.starexec.backend.BackendInitializer;
import org.starexec.constants.R;
import org.starexec.constants.PaginationQueries;
import org.starexec.data.database.Common;
import org.starexec.logger.StarLogger;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.EnumSet;

/**
 * Main application listener for StarExec web application.
 * Handles application startup and shutdown lifecycle events.
 *
 * This class is responsible for initializing the application when the servlet
 * container starts up and cleaning up resources when it shuts down.
 */
@WebListener
public class Starexec implements ServletContextListener {

    private static final StarLogger log = StarLogger.getLogger(Starexec.class);
    private static ScheduledExecutorService scheduler;

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        log.info("StarExec application starting up...");

        try {
            // Initialize the database connection pool
            log.info("Initializing database connections...");
            Common.initialize();
            log.info("Database connections initialized successfully");

            // Initialize validation patterns
            log.info("Initializing validation patterns...");
            org.starexec.util.Validator.initialize();
            log.info("Validation patterns initialized successfully");

            // Load pagination SQL query templates from external config path
            try {
                PaginationQueries.loadPaginationQueries();
                log.info("Pagination queries loaded successfully");
            } catch (Exception e) {
                log.error("Failed to load pagination queries", e);
            }

            // Initialize the backend execution system via the centralized initializer.
            try {
                BackendInitializer.initialize();
                Backend backend = BackendInitializer.getBackend();
                if (backend != null) {
                    log.info("Initialized backend: " + backend.getClass().getSimpleName());
                } else {
                    log.warn("No backend configured - job execution will not be available");
                }
            } catch (Exception e) {
                log.error("Failed to initialize backend", e);
            }

            // Set build info as application attributes
            ServletContext context = sce.getServletContext();
            context.setAttribute("buildVersion", R.buildVersion);
            context.setAttribute("buildDate", R.buildDate != null
                    ? R.buildDate : R.buildDateRaw);
            context.setAttribute("buildUser", R.buildUser);
            context.setAttribute("starexecRoot", context.getContextPath());
            context.setAttribute("contactEmail", R.CONTACT_EMAIL);

            // Initialize and start periodic tasks scheduler
            log.info("Initializing periodic tasks scheduler...");
            initializePeriodicTasks();
            log.info("Periodic tasks scheduler initialized successfully");

            log.info("StarExec application startup completed successfully");

        } catch (Exception e) {
            log.fatal("Failed to initialize StarExec application", e);
            throw new RuntimeException("Application startup failed", e);
        }
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        log.info("StarExec application shutting down...");

        try {
            // Shutdown periodic tasks scheduler
            if (scheduler != null && !scheduler.isShutdown()) {
                log.info("Shutting down periodic tasks scheduler...");
                scheduler.shutdown();
                try {
                    if (!scheduler.awaitTermination(30, TimeUnit.SECONDS)) {
                        log.warn("Periodic tasks didn't terminate gracefully, forcing shutdown");
                        scheduler.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    log.warn("Interrupted while waiting for scheduler shutdown", e);
                    scheduler.shutdownNow();
                    Thread.currentThread().interrupt();
                }
                log.info("Periodic tasks scheduler shut down successfully");
            }

            // Cleanup backend resources via centralized backend getter
            try {
                Backend backend = BackendInitializer.getBackend();
                if (backend != null) {
                    log.info("Cleaning up backend resources...");
                    backend.destroyIf();
                    log.info("Backend resources cleaned up successfully");
                } else {
                    log.info("No backend to clean up");
                }
            } catch (Exception e) {
                log.error("Error cleaning up backend resources", e);
            }

            // Clean up database connections
            log.info("Cleaning up database connections...");
            Common.release();
            log.info("Database connections cleaned up successfully");

            log.info("StarExec application shutdown completed");

        } catch (Exception e) {
            log.error("Error during application shutdown", e);
        }
    }

    /**
     * Initializes and starts all periodic tasks defined in PeriodicTasks enum.
     * Tasks are scheduled based on their configured delay, period, and time unit.
     * Tasks marked as fullInstanceOnly will only run if a working backend is configured.
     */
    private void initializePeriodicTasks() {
        // Create a scheduled thread pool for periodic tasks
        // Size based on number of tasks (use a reasonable thread pool size)
        int taskCount = EnumSet.allOf(PeriodicTasks.PeriodicTask.class).size();
        scheduler = Executors.newScheduledThreadPool(Math.min(taskCount, 10));

        log.info("Starting periodic tasks...");
        int scheduledCount = 0;

        for (PeriodicTasks.PeriodicTask task : EnumSet.allOf(PeriodicTasks.PeriodicTask.class)) {
            // Check if this task should only run on full instances
            // Full instance means a working backend (SGE, OAR, LOCAL, etc.)
            if (task.fullInstanceOnly) {
                if (!BackendInitializer.isBackendInitialized()) {
                    log.info("Skipping task (fullInstanceOnly=true, backend not initialized): " + task.name());
                    continue;
                }
            }

            // Schedule the task
            long delay = task.delay;
            long period = task.period.get();
            TimeUnit unit = task.unit;

            scheduler.scheduleAtFixedRate(
                    task.task,
                    delay,
                    period,
                    unit
            );

            scheduledCount++;
            log.info("Scheduled periodic task: " + task.name() +
                    " (delay=" + delay + unit + ", period=" + period + unit + ")");
        }

        log.info("Scheduled " + scheduledCount + " periodic tasks");
    }
}
