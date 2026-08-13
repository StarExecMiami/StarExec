package org.starexec.data.database;

import org.starexec.data.to.JobStatus;
import org.starexec.data.to.User;
import org.starexec.logger.StarLogger;
import org.starexec.util.Mail;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class Notifications {
	private Notifications() {
	} // Class cannot be instantiated

	private static final StarLogger log = StarLogger.getLogger(Notifications.class);

	/**
	 * @param user ID of User
	 * @param job ID of Job
	 * @return True if user is subscribed to job, false otherwise
	 */
	public static boolean isUserSubscribedToJob(int user, int job) throws SQLException {
		Connection con = null;
		PreparedStatement ps = null;
		ResultSet results = null;
		try {
			con = Common.getConnection();
			ps = con.prepareStatement("SELECT * FROM starexec.UserSubscribedToJob(?,?)");
			ps.setInt(1, user);
			ps.setInt(2, job);
			results = ps.executeQuery();
			if (results.next()) {
				return results.getBoolean(1);
			}
			return false;
		} finally {
			Common.safeClose(results);
			Common.safeClose(ps);
			Common.safeClose(con);
		}
	}

	/**
	 * Subscribe User u to Job j
	 *
	 * @param user ID of User
	 * @param job ID of Job
	 */
	public static void subscribeUserToJob(int user, int job) throws SQLException {
		Connection con = null;
		PreparedStatement ps = null;
		try {
			con = Common.getConnection();
			ps = con.prepareStatement("SELECT starexec.SubscribeUserToJob(?,?)");
			ps.setInt(1, user);
			ps.setInt(2, job);
			Common.executeAndDrain(ps);
		} finally {
			Common.safeClose(ps);
			Common.safeClose(con);
		}
	}

	/**
	 * Unsubscribe user from job
	 *
	 * @param user ID of User
	 * @param job ID of Job
	 */
	public static void unsubscribeUserToJob(int user, int job) throws SQLException {
		Connection con = null;
		PreparedStatement ps = null;
		try {
			con = Common.getConnection();
			// The routine is named UnsubscribeUserFromJob. Calling UnsubscribeUserToJob
			// raised undefined_function every time, so unsubscribing has never worked;
			// no versioned migration ever defined that name as an alias.
			ps = con.prepareStatement("SELECT starexec.UnsubscribeUserFromJob(?,?)");
			ps.setInt(1, user);
			ps.setInt(2, job);
			Common.executeAndDrain(ps);
		} finally {
			Common.safeClose(ps);
			Common.safeClose(con);
		}
	}

	/**
	 * Update the last seen status of a Job to status. After we have sent a notification, we need to record the current
	 * status of the Job so that we can be notified when it changes again.
	 *
	 * @param userId
	 * @param job
	 * @param status
	 */
	private static void updateNotificationJobStatus(int userId, int job, JobStatus status) throws SQLException {
		log.debug(
				"updateNotificationJobStatus",
				"user: " + userId + "    job: " + job + "   status " + status.toString()
		);
		Connection con = null;
		PreparedStatement ps = null;
		try {
			con = Common.getConnection();
			ps = con.prepareStatement("SELECT starexec.UpdateNotificationJobStatus(?,?,?)");
			ps.setInt(1, userId);
			ps.setInt(2, job);
			ps.setString(3, status.name());
			Common.executeAndDrain(ps);
		} finally {
			Common.safeClose(ps);
			Common.safeClose(con);
		}
	}

	/**
	 * Send email notifications for Jobs with changed status. Query the DB for Jobs whose status has changed since last
	 * we checked. Send email notifications to users subscribed to those jobs, then record the new status so we will
	 * know if it changes again in the future.
	 */
	public static void sendEmailNotifications() {
		final String method = "sendEmailNotifications";
		try {
			Connection con = null;
			PreparedStatement ps = null;
			ResultSet results = null;
			try {
				con = Common.getConnection();
				ps = con.prepareStatement("SELECT * FROM starexec.NotifyUsersOfJobs()");
				results = ps.executeQuery();
				User user = new User();
				JobStatus status;
				int job;
				int userId;
				while (results.next()) {
					userId = results.getInt("user");
					user.setId(userId);
					user.setEmail(results.getString("email"));
					user.setFirstName(results.getString("firstName"));
					user.setLastName(results.getString("lastName"));
					job = results.getInt("job");
					status = JobStatus.valueOf(results.getString("status"));
					Mail.notifyUserOfJobStatus(user, job, status);
					updateNotificationJobStatus(userId, job, status);
				}
			} finally {
				Common.safeClose(results);
				Common.safeClose(ps);
				Common.safeClose(con);
			}
		} catch (SQLException e) {
			log.error(method, e);
		}
	}
}
