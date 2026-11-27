package org.starexec.data.database;

import org.starexec.logger.StarLogger;

import java.sql.Date;
import java.sql.SQLException;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Analytics keeps a record of how often events happen.
 * A count is kept of how many times an events occurred per day.
 * A list is kept of unique users that have triggered an event per day.
 * Events must be added both here and in the `analytics_events` table.
 */
public enum Analytics {
	JOB_ATTRIBUTES,
	JOB_CREATE,
	JOB_CREATE_QUICKJOB,
	JOB_DETAILS,
	JOB_PAUSE,
	JOB_RESUME,
	PAGEVIEW_HELP,
	PYTHON_API_LOGIN,
	STAREXEC_DEPLOY,
	STAREXECCOMMAND_LOGIN;

	private static final StarLogger log = StarLogger.getLogger(Analytics.class);

	private volatile int id;
	private final ConcurrentHashMap<Date, Data> events;

	private static final class Data {
		private final Set<Integer> users = ConcurrentHashMap.newKeySet();
		private final AtomicInteger count = new AtomicInteger(0);

		void record(Integer user) {
			count.incrementAndGet();
			if (user != null) {
				users.add(user);
			}
		}
	}

	/**
	 * Constructor.
	 * Retrieves this event `id` from the `analytics_events` table.
	 * If this event is not found in the table, set the `id` to `-1` and move
	 * on with life. It is not worth throwing an exception.
	 */
	Analytics() {
		events = new ConcurrentHashMap<>();
		id = resolveEventId(true);
	}

	/**
	 * Look up the ID for this event in the DB
	 * @param logOnMissing whether missing/error lookup should log an error
	 * @return ID if found, -1 if not found or pool is uninitialized
	 */
	private int resolveEventId(boolean logOnMissing) {
		if (!Common.isPoolInitialized()) {
			return -1;
		}
		int resolved = -1;
		try {
			Integer queried = Common.query(
					"{SELECT * FROM starexec.GetEventId(?)}",
					procedure -> procedure.setString(1, this.name()),
					results -> results.next() ? results.getInt("event_id") : -1
			);
			resolved = (queried != null && queried > 0) ? queried : -1;
			if (resolved == -1 && logOnMissing) {
				StarLogger.getLogger(Analytics.class).error("Event not found in database: " + this.name());
			}
		} catch (SQLException | RuntimeException e) {
			if (logOnMissing) {
				/* Cannot reliably access `static log` while enum constants are still initializing. */
				StarLogger.getLogger(Analytics.class).error("Event not found in database: " + this.name());
			}
		}
		return resolved;
	}

	private int ensureEventId() {
		int current = id;
		if (current == -1) {
			current = resolveEventId(false);
			if (current != -1) {
				id = current;
			}
		}
		return current;
	}

	/**
	 * Record an occurrence of this event initiated by a particular user
	 * @param userId the user who initiated this event
	 */
	public void record(Integer userId) {
		todaysData().record(userId);
	}

	/**
	 * Record an occurrence of this event
	 * that was not initiated by a particular user
	 */
	public void record() {
		record(null);
	}

	public static void saveToDB() {
		Date now = now();
		for (Analytics event : Analytics.values()) {
			int eventId = event.ensureEventId();
			if (eventId == -1) {
				continue;
			}
			for (Map.Entry<Date, Data> kv : event.events.entrySet()) {
				Date date = kv.getKey();
				Data v = kv.getValue();
				int delta = v.count.getAndSet(0);
				if (delta > 0) {
					try {
						event.saveToDB(eventId, date, delta);
					} catch (SQLException e) {
						v.count.addAndGet(delta);
						log.error("Cannot record event: " + event.name(), e);
						continue;
					}
				}
				for (Integer user : v.users) {
					try {
						event.saveUserToDB(eventId, date, user);
						// Drain persisted users so subsequent flushes do not re-attempt deleted accounts.
						v.users.remove(user);
					} catch (SQLException e) {
						if (isForeignKeyViolation(e)) {
							// Purge deleted/non-existent user IDs instead of retrying every 10 minutes
							// or persisting recurring rows to error_logs.
							v.users.remove(user);
							log.info("Discarding analytics user " + user + " for event " + event.name()
									+ " (foreign key violation: " + e.getMessage() + ")");
						} else {
							log.error("Cannot record user " + user + " event: " + event.name(), e);
						}
					}
				}
				if (!date.equals(now) && v.count.get() == 0 && v.users.isEmpty()) {
					event.events.remove(date, v);
				}
			}
		}
	}

	private static boolean isForeignKeyViolation(SQLException e) {
		for (Throwable t = e; t != null; t = t.getCause()) {
			if (t instanceof SQLException sqlEx && "23503".equals(sqlEx.getSQLState())) {
				return true;
			}
		}
		for (SQLException next = e.getNextException(); next != null; next = next.getNextException()) {
			if ("23503".equals(next.getSQLState())) {
				return true;
			}
		}
		return false;
	}

	private void saveToDB(int eventId, Date date, int count) throws SQLException {
		Common.update(
			"{call starexec.RecordEvent(?,?,?)}",
			procedure -> {
				procedure.setInt(1, eventId);
				procedure.setDate(2, date);
				procedure.setInt(3, count);
			}
		);
	}

	private void saveUserToDB(int eventId, Date date, int userId) throws SQLException {
		Common.update(
			"{call starexec.RecordEventUser(?,?,?)}",
			procedure -> {
				procedure.setInt(1, eventId);
				procedure.setDate(2, date);
				procedure.setInt(3, userId);
			}
		);
	}

	/**
	 * Get the Data for this event today
	 * If Data does not already exist for today, create it
	 * @return Data
	 */
	private Data todaysData() {
		return events.computeIfAbsent(now(), k -> new Data());
	}

	/**
	 * @return SQL Date that represents the current date
	 */
	private static Date now() {
		final java.util.Date now = new java.util.Date();
		long roundedTime = now.getTime() - (now.getTime() % 86400000);
		return new Date(roundedTime);
	}
}
