package org.starexec.util;

import org.starexec.logger.StarLogger;

public abstract class RobustRunnable implements Runnable {
	private static final StarLogger log = StarLogger.getLogger(RobustRunnable.class);

	protected final String name;

	abstract protected void dorun();

	public RobustRunnable(String _name) {
		name = _name;
	}

	@Override
	public void run() {
		Timer timer = new Timer();
		try {
			log.debug(name + " (periodic)");
			dorun();
		} catch (Throwable e) {
			log.warn(name + " caught throwable: " + e, e);
		} finally {
			log.debug(name + " completed one periodic execution in " + timer.getTime() + " milliseconds.");
		}
	}
}
