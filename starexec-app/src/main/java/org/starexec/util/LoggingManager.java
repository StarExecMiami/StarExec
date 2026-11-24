package org.starexec.util;

import org.starexec.logger.StarLevel;
import org.starexec.logger.StarLogger;


public class LoggingManager {
	// private static final StarLogger log = StarLogger.getLogger(LoggingManager.class);

	public static void setLoggingLevel(StarLevel level) {
		StarLogger.getRootLogger().setLevel(level);
	}

	public static boolean setLoggingLevelForClass(StarLevel level, String className) {
	    if (className == null || className.trim().isEmpty()) {
		return false;
	    }
	    try {
		Class.forName(className, false, LoggingManager.class.getClassLoader());
	    } catch (ClassNotFoundException | LinkageError e) {
		return false;
	    }
	    StarLogger logger = StarLogger.getLogger(className);
	    if (logger == null) {
		return false;
	    }
	    logger.setLevel(level);
	    return true;
	}
}
