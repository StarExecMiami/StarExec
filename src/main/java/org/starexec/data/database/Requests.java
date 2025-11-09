package org.starexec.data.database;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.starexec.data.to.CommunityRequest;
import org.starexec.data.to.User;
import org.starexec.exceptions.StarExecDatabaseException;
import org.starexec.logger.StarLogger;
import org.starexec.util.DataTablesQuery;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedList;
import java.util.List;

/**
 * Handles all database interaction for the various requests throughout the system. This includes user activation,
 * community and password reset requests
 */
public class Requests {
	private static final StarLogger log = StarLogger.getLogger(Requests.class);

	/**
	 * Adds an activation code to the database for a given user (used during registration)
	 *
	 * @param user the user to add an activation code to the database for
	 * @param con the database connection maintaining the transaction
	 * @param code the new activation code to add
	 * @return True if the operation was a success, false otherwise
	 * @author Todd Elvers
	 */
	protected static boolean addActivationCode(Connection con, User user, String code) {
		PreparedStatement ps = null;
		try {
			// Add a new entry to the VERIFY table
			ps = con.prepareStatement("SELECT starexec.AddCode(?, ?)");
			ps.setInt(1, user.getId());
			ps.setString(2, code);

			// Apply update to database (function returns void/scalar) and ignore any result
			ps.execute();
			log.info(String.format("New email activation code [%s] added to VERIFY for user [%s]", code,
			                       user.getFullName()
			));
			return true;
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		} finally {
			Common.safeClose(ps);
		}

		log.debug(String.format("Adding activation record failed for [%s]", user.getFullName()));
		return false;
	}

	/**
	 * Adds an entry to the community request table in a transaction-safe manner
	 *
	 * @param code the code used in hyperlinks to safely reference this request
	 * @param con the connection maintaining the database transaction
	 * @param user user initiating the request
	 * @param message the message to the leaders of the community
	 * @param communityId the id of the community this request is for
	 * @return True if the operation was a success, false otherwise
	 * @author Todd Elvers
	 */
	protected static boolean addCommunityRequest(
			Connection con, User user, int communityId, String code, String message
	) {
		PreparedStatement ps = null;
		try {
			// Add a new entry to the community_request table
			ps = con.prepareStatement("SELECT starexec.AddCommunityRequest(?, ?, ?, ?)");
			ps.setInt(1, user.getId());
			ps.setInt(2, communityId);
			ps.setString(3, code);
			ps.setString(4, message);

			ps.execute();
			log.debug(String.format("Added invitation record for user [%s] on community %d", user, communityId));
			return true;
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		} finally {
			Common.safeClose(ps);
		}

		log.debug(String.format("Add invitation record failed for user [%s] on community %d", user, communityId));
		return false;
	}

	/**
	 * Adds a request to join a community for a given user and community
	 *
	 * @param user the user who wants to join a community
	 * @param communityId the id of the community the user wants to join
	 * @param code the code used in hyperlinks to safely reference this request
	 * @param message the message the user wrote to the leaders of this community
	 * @return True if the operation was a success, false otherwise
	 * @author Todd Elvers
	 */
	public static boolean addCommunityRequest(User user, int communityId, String code, String message) {
		Connection con = null;

		try {
			con = Common.getConnection();
			return Requests.addCommunityRequest(con, user, communityId, code, message);
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		} finally {
			Common.safeClose(con);
		}

		return false;
	}

	/**
	 * Adds a request to have a user's password reset; prevents duplicate entries in the password reset table by first
	 * deleting any previous entries for this user
	 *
	 * @param userId the id of the user requesting the password reset
	 * @param code the code to add to the password reset table (for hyperlinking)
	 * @return True if the operation was a success, false otherwise
	 * @author Todd Elvers
	 */
	public static boolean addPassResetRequest(int userId, String code) {
		Connection con = null;
		PreparedStatement ps = null;
		try {
			con = Common.getConnection();
			ps = con.prepareStatement("SELECT starexec.AddPassResetRequest(?, ?)");
			ps.setInt(1, userId);
			ps.setString(2, code);

			ps.execute();
			return true;
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		} finally {
			Common.safeClose(con);
			Common.safeClose(ps);
		}

		return false;
	}

	/**
	 * Adds a user to their community and deletes their entry in the community request table
	 *
	 * @param userId the user id of the newly registered user
	 * @param communityId the community the newly registered user is joining
	 * @return True if the operation was a success, false otherwise
	 * @author Todd Elvers
	 */
	public static boolean approveCommunityRequest(int userId, int communityId) {
		Connection con = null;
		PreparedStatement ps = null;
		try {
			con = Common.getConnection();
			ps = con.prepareStatement("SELECT starexec.ApproveCommunityRequest(?, ?)");
			ps.setInt(1, userId);
			ps.setInt(2, communityId);

			ps.execute();
			return true;
		} catch (SQLException e) {
			if ("P0002".equals(e.getSQLState())) {
				log.warn("Community request for user " + userId + " and community " + communityId + " not found");
			} else {
				log.error("approveCommunityRequest", e.getMessage(), e);
			}
		} finally {
			Common.safeClose(con);
			Common.safeClose(ps);
		}

		return false;
	}

	/**
	 * Deletes a community request given a user id and community id, and if the user is unregistered, they are also
	 * removed from the users table
	 *
	 * @param userId the user id associated with the invite
	 * @param communityId the communityId associated with the invite
	 * @return True if the operation was a success, false otherwise
	 * @author Todd Elvers
	 */
	public static boolean declineCommunityRequest(int userId, int communityId) {
		Connection con = null;
		PreparedStatement ps = null;
		try {
			con = Common.getConnection();
			ps = con.prepareStatement("SELECT starexec.DeclineCommunityRequest(?, ?)");
			ps.setInt(1, userId);
			ps.setInt(2, communityId);

			ps.execute();
			return true;
		} catch (SQLException e) {
			if ("P0002".equals(e.getSQLState())) {
				log.warn("Community request for user " + userId + " and community " + communityId + " not found");
			} else {
				log.error("declineCommunityRequest", e.getMessage(), e);
			}
		} finally {
			Common.safeClose(con);
			Common.safeClose(ps);
		}

		return false;
	}

	/**
	 * Retrieves a community request from the database given the user id
	 *
	 * @param userId the user id of the request to retrieve
	 * @return The request associated with the user id
	 * @author Todd Elvers
	 */
	public static CommunityRequest getCommunityRequest(int userId) {
		Connection con = null;
		ResultSet results = null;
		PreparedStatement ps = null;
		try {
			con = Common.getConnection();
			ps = con.prepareStatement("SELECT * FROM starexec.GetCommunityRequestById(?)");
			ps.setInt(1, userId);
			results = ps.executeQuery();

			return resultsToCommunityRequest(results);
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
	 * Retrieves a community request from the database given the user id
	 *
	 * @param userId the user id of the request to retrieve
	 * @return The request associated with the user id
	 * @author Todd Elvers
	 */
	public static boolean communityRequestExistsForUser(int userId, int community) throws SQLException {
		Connection con = null;
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			con = Common.getConnection();
			ps = con.prepareStatement("SELECT * FROM starexec.GetCommunityRequestForUser(?,?)");
			ps.setInt(1, userId);
			ps.setInt(2, community);
			rs = ps.executeQuery();
			return rs.next();
		} finally {
			Common.safeClose(rs);
			Common.safeClose(ps);
			Common.safeClose(con);
		}
	}

	/**
	 * Retrieves a community request from the database given a code
	 *
	 * @param code the code of the request to retrieve
	 * @return The request object associated with the request code
	 * @author Todd Elvers
	 */
	public static CommunityRequest getCommunityRequest(String code) {
		Connection con = null;
		ResultSet results = null;
		PreparedStatement ps = null;
		try {
			con = Common.getConnection();
			ps = con.prepareStatement("SELECT * FROM starexec.GetCommunityRequestByCode(?)");
			ps.setString(1, code);
			results = ps.executeQuery();

			return resultsToCommunityRequest(results);
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		} finally {
			Common.safeClose(con);
			Common.safeClose(results);
			Common.safeClose(ps);
		}

		return null;
	}

	/**
	 * Gets the number of community requests for a given community.
	 *
	 * @param communityId The community to get the number of requests for.
	 * @return the number of community requests for the community.
	 */
	public static int getCommunityRequestCountForCommunity(int communityId) throws StarExecDatabaseException {
		Connection con = null;
		PreparedStatement ps = null;
		ResultSet results = null;
		try {
			con = Common.getConnection();
			ps = con.prepareStatement("SELECT * FROM starexec.GetCommunityRequestCountForCommunity(?)");
			ps.setInt(1, communityId);
			results = ps.executeQuery();
			return processRequestCountResults(results);
		} catch (Exception e) {
			log.error(e.getMessage(), e);
			throw new StarExecDatabaseException(
					"Could not get community request count for community with id=" + communityId, e);
		} finally {
			Common.safeClose(con);
			Common.safeClose(results);
			Common.safeClose(ps);
		}
	}

	/**
	 * Returns the number of community_requests
	 *
	 * @return the number of community_requests
	 */
	public static int getCommunityRequestCount() throws StarExecDatabaseException {
		Connection con = null;
		PreparedStatement ps = null;
		ResultSet results = null;
		try {
			con = Common.getConnection();
			ps = con.prepareStatement("SELECT * FROM starexec.GetCommunityRequestCount()");
			results = ps.executeQuery();
			return processRequestCountResults(results);
		} catch (Exception e) {
			log.error(e.getMessage(), e);
			throw new StarExecDatabaseException("Could not get community request count for all communities.", e);
		} finally {
			Common.safeClose(con);
			Common.safeClose(results);
			Common.safeClose(ps);
		}
	}

	private static int processRequestCountResults(ResultSet requestCountResults) throws SQLException {
		int reservationCount = 0;
		if (requestCountResults.next()) {
			reservationCount = requestCountResults.getInt("requestCount");
		}
		return reservationCount;
	}

	/**
	 * Gets all pending requests to join a given community.
	 *
	 * @param query a DataTablesQuery object
	 * @param communityId the community to get requests for.
	 * @return A list of requests to display, or null on error.
	 * @author Albert Giegerich
	 */
	public static List<CommunityRequest> getPendingCommunityRequestsForCommunity(
			DataTablesQuery query, int communityId
	) throws StarExecDatabaseException {
		Connection con = null;
		PreparedStatement ps = null;
		ResultSet results = null;

		try {
			con = Common.getConnection();

			ps = con.prepareStatement("SELECT * FROM starexec.GetNextPageOfPendingCommunityRequestsForCommunity(?, ?, ?)");
			ps.setInt(1, query.getStartingRecord());
			ps.setInt(2, query.getNumRecords());
			ps.setInt(3, communityId);
			results = ps.executeQuery();
			return processGetCommunityRequestResults(results);
		} catch (Exception e) {
			log.error(e.getMessage(), e);
			throw new StarExecDatabaseException("Error while retrieving next page of pending community requests.", e);
		} finally {
			Common.safeClose(con);
			Common.safeClose(results);
			Common.safeClose(ps);
		}
	}

	/**
	 * Gets all pending request to join communities
	 *
	 * @param query a DataTablesQuery query
	 * @return A list of requests to display, or null on error.
	 */
	public static List<CommunityRequest> getPendingCommunityRequests(DataTablesQuery query)
			throws StarExecDatabaseException {
		Connection con = null;
		PreparedStatement ps = null;
		ResultSet results = null;

		try {
			con = Common.getConnection();

			ps = con.prepareStatement("SELECT * FROM starexec.GetNextPageOfPendingCommunityRequests(?, ?)");
			ps.setInt(1, query.getStartingRecord());
			ps.setInt(2, query.getNumRecords());
			results = ps.executeQuery();
			return processGetCommunityRequestResults(results);
		} catch (Exception e) {
			log.error(e.getMessage(), e);
			throw new StarExecDatabaseException("Error while retrieving next page of pending community requests.", e);
		} finally {
			Common.safeClose(con);
			Common.safeClose(results);
			Common.safeClose(ps);
		}
	}

	/**
	 * Gets all pending request to join communities
	 *
	 * @param results SQL results to convert to a CommunityRequest
	 * @return A list of requests to display, or null on error
	 */
	private static List<CommunityRequest> processGetCommunityRequestResults(ResultSet results) throws SQLException {
		List<CommunityRequest> requests = new LinkedList<>();

		while (results.next()) {
			CommunityRequest req = new CommunityRequest();
			req.setUserId(results.getInt("user_id"));
			req.setCommunityId(results.getInt("community"));
			req.setCode(results.getString("code"));
			req.setMessage(results.getString("message"));
			req.setCreateDate(results.getTimestamp("created"));

			requests.add(req);
		}
		return requests;
	}

	private static CommunityRequest resultsToCommunityRequest(ResultSet results) throws SQLException {
		List<CommunityRequest> requests = processGetCommunityRequestResults(results);
		return !requests.isEmpty() ? requests.get(0) : null;
	}

	/**
	 * Checks an activation code provided by the user against the code in table VERIFY and if they match, removes that
	 * entry in VERIFY, and adds an entry to USER_ROLES
	 *
	 * @param codeFromUser the activation code provided by the user
	 * @return The ID of the user on success and -1 on error
	 * @author Todd Elvers
	 */
	public static int redeemActivationCode(String codeFromUser) {
		Connection con = null;
		PreparedStatement stmt = null;
		ResultSet rs = null;
		try {

			con = Common.getConnection();

			stmt = con.prepareStatement("SELECT RedeemActivationCode(?)");
			stmt.setString(1, codeFromUser);

			rs = stmt.executeQuery();
			rs.next();
			int userId = rs.getInt(1);
			log.info(String.format("Activation code %s redeemed by user %d", codeFromUser, userId));
			return userId;
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		} finally {
			Common.safeClose(con);
			Common.safeClose(rs);
			Common.safeClose(stmt);
		}

		return -1;
	}

	/**
	 * Gets the user_id associated with the given code and deletes the corresponding entry in the password reset table
	 *
	 * @param code the UUID to check the password reset table with
	 * @return the id of the user requesting the password reset if the code provided matches one in the password reset
	 * table, -1 otherwise
	 * @author Todd Elvers
	 */
	public static int redeemPassResetRequest(String code) {
		Connection con = null;
		PreparedStatement stmt = null;
		ResultSet rs = null;
		try {
			con = Common.getConnection();
			stmt = con.prepareStatement("SELECT RedeemPassResetRequestByCode(?)");
			stmt.setString(1, code);
			rs = stmt.executeQuery();
			rs.next();
			return rs.getInt(1);
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		} finally {
			Common.safeClose(con);
			Common.safeClose(rs);
			Common.safeClose(stmt);
		}

		return -1;
	}

	/**
	 * Adds a change email request to the database.
	 *
	 * @param userId The id of the user making the request.
	 * @param newEmail The new email the user wants to change their account's email to.
	 * @param code The unique code to assign to this request
	 * @throws StarExecDatabaseException Whenever there is a database error
	 * @author Albert Giegerich
	 */
	public static void addChangeEmailRequest(int userId, String newEmail, String code)
			throws StarExecDatabaseException {
		Connection con = null;
		PreparedStatement ps = null;
		try {
			con = Common.getConnection();

			ps = con.prepareStatement("SELECT starexec.AddChangeEmailRequest(?, ?, ?)");
			ps.setInt(1, userId);
			ps.setString(2, newEmail);
			ps.setString(3, code);
			ps.execute();
		} catch (Exception e) {
			throw new StarExecDatabaseException(
					"There was an error while trying to add a change email request for user with id=" + userId +
							" newEmail=" + newEmail + " and code=" + code, e);
		} finally {
			Common.safeClose(con);
			Common.safeClose(ps);
		}
	}

	/**
	 * Retrieves a request to change email addresses
	 *
	 * @param userId The ID of the request to retrieve
	 * @return A pair consisting of the new email address first and the code second
	 * @throws StarExecDatabaseException If there was a database error.
	 */
	public static Pair<String, String> getChangeEmailRequest(int userId) throws StarExecDatabaseException {
		Connection con = null;
		PreparedStatement ps = null;
		ResultSet results = null;
		String newEmail = null;
		String emailChangeCodeAssociatedWithUser = null;
		try {
			con = Common.getConnection();
			ps = con.prepareStatement("SELECT * FROM starexec.GetChangeEmailRequest(?)");
			ps.setInt(1, userId);
			results = ps.executeQuery();

			// There should only be 1 result since the user id is the primary
			// key.
			if (results.next()) {
				newEmail = results.getString("new_email");
				emailChangeCodeAssociatedWithUser = results.getString("code");
			} else {
				throw new StarExecDatabaseException(
					"No change email request found for user with id=" + userId + "."
				);
			}
		} catch (Exception e) {
			throw new StarExecDatabaseException(
					"There was an error while trying to get a change email request for user with id=" + userId + ".",
					e
			);
		} finally {
			Common.safeClose(con);
			Common.safeClose(ps);
			Common.safeClose(results);
		}
		return new ImmutablePair<>(newEmail, emailChangeCodeAssociatedWithUser);
	}

	/**
	 * Deletes a change email request.
	 *
	 * @param userId the id of the user associated with the change email request.
	 * @throws StarExecDatabaseException if an error occurs in the database.
	 * @author Albert Giegerich
	 */
	public static void deleteChangeEmailRequest(int userId) throws StarExecDatabaseException {
		Connection con = null;
		PreparedStatement ps = null;
		try {
			con = Common.getConnection();
			ps = con.prepareStatement("SELECT starexec.DeleteChangeEmailRequest(?)");
			ps.setInt(1, userId);
			ps.execute();
		} catch (Exception e) {
			throw new StarExecDatabaseException(
					"There was an error while trying to delete a change email requests for user with id=" + userId +
							".", e);
		} finally {
			Common.safeClose(con);
			Common.safeClose(ps);
		}
	}

	/**
	 * Checks whether a user has an outstanding request to change their email address
	 *
	 * @param userId The ID of the user to check
	 * @return True if so and false if not or there was an error.
	 * @throws StarExecDatabaseException Whenever there was a database exception.
	 */
	public static boolean changeEmailRequestExists(int userId) throws StarExecDatabaseException {
		Connection con = null;
		PreparedStatement ps = null;
		ResultSet results = null;
		boolean changeEmailRequestExists = false;
		try {
			con = Common.getConnection();
			ps = con.prepareStatement("SELECT * FROM starexec.GetChangeEmailRequest(?)");
			ps.setInt(1, userId);
			results = ps.executeQuery();
			// results.next() will be true if there is at least one result
			if (results.next()) {
				changeEmailRequestExists = true;
			}

			log.debug("(changeEmailRequestExists) Change email request does" +
					          (changeEmailRequestExists ? " " : " not ") + "exist for user with id=" + userId);
		} catch (Exception e) {
			throw new StarExecDatabaseException(
					"There was an error while trying to get a change email requests for user with id=" + userId + ".",
					e
			);
		} finally {
			Common.safeClose(con);
			Common.safeClose(ps);
			Common.safeClose(results);
		}

		return changeEmailRequestExists;
	}
}
