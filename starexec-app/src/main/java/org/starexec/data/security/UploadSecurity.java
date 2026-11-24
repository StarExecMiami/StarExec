package org.starexec.data.security;

import org.starexec.app.RESTHelpers;
import org.starexec.data.database.UploadJobQueue;
import org.starexec.data.database.Uploads;
import org.starexec.data.database.Users;
import org.starexec.data.to.BenchmarkUploadStatus;
import org.starexec.data.to.UploadJob;
import org.starexec.logger.StarLogger;

import java.sql.SQLException;
import java.util.Optional;

/**
 * Determines whether users have authorization to view BenchmarkUploadStatus data
 */
public class UploadSecurity {
	private static final StarLogger log = StarLogger.getLogger(UploadSecurity.class);

	/**
	 * Determines whether a user can see a BenchmarkUploadStatus object that owns the given unvalidated benchmark
	 *
	 * @param userId The ID of the user making the request
	 * @param unvalidatedBenchmarkId The ID of the unvalidated benchmark
	 * @return A ValidatorStatusCode object
	 */
	public static ValidatorStatusCode canViewUnvalidatedBenchmarkOutput(int userId, int unvalidatedBenchmarkId) {
		if (GeneralSecurity.hasAdminReadPrivileges(userId)) {
			return new ValidatorStatusCode(true);
		}
		BenchmarkUploadStatus status = Uploads.getUploadStatusForInvalidBenchmarkId(unvalidatedBenchmarkId);
		if (status != null && status.getUserId() != userId) {
			return new ValidatorStatusCode(false, "You may only view your own benchmark uploads");
		}
		return new ValidatorStatusCode(true);
	}

	/**
	 * Check if uploads are currently frozen
	 * @return True if uploads are currently prohibitted, false otherwise
	 */
	public static boolean uploadsFrozen() {
		long t0 = System.currentTimeMillis();
		try {
			boolean frozen = RESTHelpers.freezePrimitives();
			log.debug("uploadsFrozen: freezePrimitives returned="+frozen+" in "+(System.currentTimeMillis()-t0)+" ms");
			return frozen;
		} catch (SQLException e) {
			log.error("uploadsFrozen: SQL exception (treating as frozen)", e);
			return true;
		}
	}

	/**
	 * Validates authorization and lifecycle eligibility to delete an async upload history row.
	 */
	public static ValidatorStatusCode canUserDeleteAsyncUploadJob(long jobId, int callerId) {
		Optional<UploadJob> jobOpt = UploadJobQueue.getJob(jobId);
		if (jobOpt.isEmpty()) {
			return new ValidatorStatusCode(false, "Upload history entry could not be found");
		}
		UploadJob job = jobOpt.get();
		if (job.getUserId() != callerId && !GeneralSecurity.hasAdminWritePrivileges(callerId)) {
			return new ValidatorStatusCode(false, "You do not have permission to delete upload history belonging to another user");
		}
		if (!job.isTerminal()) {
			return new ValidatorStatusCode(false, "Cannot delete an active upload job (" + job.getStatus() + ")");
		}
		return new ValidatorStatusCode(true);
	}

	/**
	 * Validates authorization and minimum age eligibility to delete a legacy benchmark upload history row.
	 */
	public static ValidatorStatusCode canUserDeleteLegacyUpload(int statusId, int callerId) {
		BenchmarkUploadStatus status = Uploads.getBenchmarkStatus(statusId);
		if (status == null) {
			return new ValidatorStatusCode(false, "Upload history entry could not be found");
		}
		if (status.getUserId() != callerId && !GeneralSecurity.hasAdminWritePrivileges(callerId)) {
			return new ValidatorStatusCode(false, "You do not have permission to delete upload history belonging to another user");
		}
		if (!Uploads.isLegacyUploadEligibleForDeletion(status)) {
			return new ValidatorStatusCode(false, "Incomplete legacy uploads can only be deleted after 1 hour");
		}
		return new ValidatorStatusCode(true);
	}

	/**
	 * Validates authorization to clear eligible upload history entries for a user.
	 */
	public static ValidatorStatusCode canUserClearUploadHistory(int targetUserId, int callerId) {
		if (Users.get(targetUserId) == null) {
			return new ValidatorStatusCode(false, "The given user could not be found");
		}
		if (targetUserId != callerId && !GeneralSecurity.hasAdminWritePrivileges(callerId)) {
			return new ValidatorStatusCode(false, "You do not have permission to clear upload history belonging to another user");
		}
		return new ValidatorStatusCode(true);
	}
}
