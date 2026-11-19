package org.starexec.data.database;

import org.starexec.data.to.Report;
import org.starexec.logger.StarLogger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * Handles database interaction for the weekly reports.
 *
 * @author Albert Giegerich
 */
public class Reports {
	private static final StarLogger log = StarLogger.getLogger(Reports.class);

	/**
	 * Set the number of occurrences for an event not related to a queue.
	 *
	 * @param eventName   the name of the event.
	 * @param occurrences the number of times the event occurred.
	 * @return True on success and false on error
	 * @author Albert Giegerich
	 */
	public static boolean setEventOccurrencesNotRelatedToQueue(String eventName, int occurrences) {
		return setEventOccurrences(eventName, occurrences, null);
	}

	/**
	 * Add occurrences to an event not related to a queue.
	 *
	 * @param eventName   the name of the event.
	 * @param occurrences the number of times the event occurred.
	 * @return True on success and false on error
	 * @author Albert Giegerich
	 */
	public static boolean addToEventOccurrencesNotRelatedToQueue(String eventName, int occurrences) {
		return addToEventOccurrences(eventName, occurrences, null);
	}

	/**
	 * As above, on a connection the caller owns, so the counter commits with whatever it is
	 * counting rather than on a connection of its own.
	 *
	 * @throws SQLException so a failed counter fails its caller's transaction. Reporting an
	 * event that did not happen is a silent accounting error, which is the kind these
	 * counters exist to avoid.
	 */
	public static void addToEventOccurrencesNotRelatedToQueue(String eventName, int occurrences, Connection con)
			throws SQLException {
		addToEventOccurrences(eventName, occurrences, null, con);
	}

	/**
	 * Add occurrences to an event related to a queue
	 *
	 * @param eventName   the name of the event.
	 * @param occurrences the number of times the event occurred.
	 * @param queueName   the name of the queue related to the event.
	 * @return True on success and false on error
	 */
	/** As {@link #addToEventOccurrencesForQueue(String, int, String)}, on a borrowed connection. */
	public static void addToEventOccurrencesForQueue(
			String eventName, int occurrences, String queueName, Connection con) throws SQLException {
		addToEventOccurrences(eventName, occurrences, queueName, con);
	}

	public static boolean addToEventOccurrencesForQueue(String eventName, int occurrences, String queueName) {
		return addToEventOccurrences(eventName, occurrences, queueName);
	}

	/**
	 * Gets every event and the number of times it occurred for events that are not
	 * related to a queue.
	 *
	 * @return a list of ImmutablePairs representing and event and the number of
	 *         times it occurred.
	 * @author Albert Giegerich
	 */
	public static List<Report> getAllReportsNotRelatedToQueues() {
		List<Report> reports = new LinkedList<>();
		Connection con = null;
		PreparedStatement ps = null;
		ResultSet results = null;

		try {
			con = Common.getConnection();
			ps = con.prepareStatement("SELECT * FROM starexec.GetAllEventsAndOccurrencesNotRelatedToQueues()");
			results = ps.executeQuery();

			while (results.next()) {
				String event = results.getString("event_name");
				Integer occurrences = results.getInt("occurrences");
				Report report = new Report(event, occurrences);
				reports.add(report);
			}
			return reports;
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		} finally {
			Common.safeClose(con);
			Common.safeClose(ps);
			Common.safeClose(results);
		}
		return null;
	}

	/**
	 * Get every event, the number of times it occurred, and the queue it occurred
	 * on.
	 *
	 * @return a list of Reports representing the event, the number of times it
	 *         occurred, and which queue it occurred
	 *         on.
	 * @author Albert Giegerich
	 */
	public static List<List<Report>> getAllReportsForAllQueues() throws SQLException {
		LinkedList<Report> reportsForAllQueues = new LinkedList<>();
		Connection con = null;
		PreparedStatement ps = null;
		ResultSet results = null;

		try {
			con = Common.getConnection();
			ps = con.prepareStatement("SELECT * FROM starexec.GetAllEventsAndOccurrencesForAllQueues()");
			results = ps.executeQuery();

			while (results.next()) {
				String event = results.getString("event_name");
				Integer occurrences = results.getInt("occurrences");
				String queueName = results.getString("queue_name");

				Report report = new Report(event, occurrences, queueName);

				reportsForAllQueues.add(report);
			}
			return separateReportsByQueue(reportsForAllQueues);
		} finally {
			Common.safeClose(con);
			Common.safeClose(ps);
			Common.safeClose(results);
		}
	}

	/**
	 * Resets all report data by setting all occurrences to 0 and deleting any rows
	 * related to queues.
	 *
	 * @author Albert Giegerich
	 */
	public static void resetReports() {
		Connection con = null;
		PreparedStatement ps = null;
		try {
			con = Common.getConnection();
			ps = con.prepareStatement("SELECT starexec.ResetReports()");
			ps.execute();
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		} finally {
			Common.safeClose(con);
			Common.safeClose(ps);
		}
	}

	/**
	 * Inner method that sets the number of event occurrences not related to a queue
	 * if queueName is null otherwise
	 * sets
	 * the number of event occurrences related to the specified queue.
	 *
	 * @param eventName   the name of the event to set the number of occurrences for
	 * @param occurrences the number of times the event occurred
	 * @param queueName   the name of the queue for which the event occurred. Null
	 *                    the event is unrelated to a queue.
	 * @author Albert Giegerich
	 */
	private static boolean setEventOccurrences(String eventName, int occurrences, String queueName) {
		Connection con = null;
		PreparedStatement ps = null;

		try {
			con = Common.getConnection();
			if (queueName == null) {
				ps = con.prepareStatement("SELECT starexec.SetEventOccurrencesNotRelatedToQueue(?, ?)");
			} else {
				ps = con.prepareStatement("SELECT starexec.SetEventOccurrencesForQueue(?, ?, ?)");
				ps.setString(3, queueName);
			}
			ps.setString(1, eventName);
			ps.setInt(2, occurrences);
			ps.execute();
			return true;
		} catch (SQLException e) {
			if ("P0002".equals(e.getSQLState())) {
				log.warn("Event '" + eventName + "' not found in reports");
			} else {
				log.error("setEventOccurrences", e.getMessage(), e);
			}
		} finally {
			Common.safeClose(con);
			Common.safeClose(ps);
		}
		return false;
	}

	/**
	 * Inner method that adds to the number of event occurrences not related to a
	 * queue if queueName is null, otherwise
	 * adds to the number of event occurrences related to the specified queue.
	 *
	 * @param eventName   the name of the event
	 * @param occurrences the number of times the event occurred
	 * @param queueName   the name of the queue the event is related to. Null if not
	 *                    related to a queue.
	 * @return True on success and false on error
	 * @author Albert Giegerich
	 */
	private static boolean addToEventOccurrences(String eventName, int occurrences, String queueName) {
		Connection con = null;
		try {
			con = Common.getConnection();
			addToEventOccurrences(eventName, occurrences, queueName, con);
			return true;
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		} finally {
			Common.safeClose(con);
		}
		return false;
	}

	/** The statement itself, on whichever connection the caller supplies. */
	private static void addToEventOccurrences(
			String eventName, int occurrences, String queueName, Connection con) throws SQLException {
		PreparedStatement ps = null;
		try {
			if (queueName == null) {
				ps = con.prepareStatement("CALL starexec.AddToEventOccurrencesNotRelatedToQueue(?, ?)");
			} else {
				ps = con.prepareStatement("SELECT starexec.AddToEventOccurrencesForQueue(?, ?, ?)");
				ps.setString(3, queueName);
			}
			ps.setString(1, eventName);
			ps.setInt(2, occurrences);

			ps.execute();
			log.debug("Added " + occurrences + " occurrences to " + eventName +
					(queueName == null ? "" : " for queue " + queueName) + ".");
		} finally {
			Common.safeClose(ps);
		}
	}

	/**
	 * Turns a list of reports related to queues into a list of lists related to
	 * queues where each inner list is
	 * made up
	 * of reports related to a different queue.
	 *
	 * @param reports a list of reports related to queues
	 * @author Albert Giegerich
	 */
	private static List<List<Report>> separateReportsByQueue(List<Report> reports) {
		// Build a map that separates all the reports into lists based on which queue
		// they're related to.
		Map<String, List<Report>> reportMap = new HashMap<>();
		for (Report report : reports) {
			String queueName = report.getQueueName();
			if (reportMap.containsKey(queueName)) {
				reportMap.get(queueName).add(report);
			} else {
				List<Report> reportsRelatedToQueue = new LinkedList<>();
				reportsRelatedToQueue.add(report);
				reportMap.put(queueName, reportsRelatedToQueue);
			}
		}

		// Use the map to build a list of lists where each inner list contains all the
		// reports related to a single
		// queue.
		List<List<Report>> reportsSeparatedByQueue = new LinkedList<>();
		Set<String> keys = reportMap.keySet();
		for (String key : keys) {
			reportsSeparatedByQueue.add(reportMap.get(key));
		}

		return reportsSeparatedByQueue;
	}
}
