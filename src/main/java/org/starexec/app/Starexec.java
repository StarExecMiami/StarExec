package org.starexec.app;

import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

import org.starexec.constants.R;
import org.starexec.data.database.Common;
import org.starexec.logger.StarLogger;

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
    
    @Override
    public void contextInitialized(ServletContextEvent sce) {
        log.info("StarExec application starting up...");
        
        try {
            // Initialize the database connection pool
            log.info("Initializing database connections...");
            Common.initialize();
            log.info("Database connections initialized successfully");

            // Set build info as application attributes
            ServletContext context = sce.getServletContext();
            context.setAttribute("buildVersion", R.buildVersion);
            context.setAttribute("buildDate", R.buildDate);
            context.setAttribute("starexecRoot", context.getContextPath());
            context.setAttribute("contactEmail", R.CONTACT_EMAIL);


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
            // Clean up database connections
            log.info("Cleaning up database connections...");
            Common.release();
            log.info("Database connections cleaned up successfully");
            
            log.info("StarExec application shutdown completed");
            
        } catch (Exception e) {
            log.error("Error during application shutdown", e);
        }
    }
}
