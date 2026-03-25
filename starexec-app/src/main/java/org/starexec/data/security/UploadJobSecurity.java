package org.starexec.data.security;

import org.starexec.data.database.UploadJobQueue;
import org.starexec.data.to.UploadJob;

/**
 * Security checks for upload job operations.
 */
public class UploadJobSecurity {

	/**
	 * Checks whether a user can view the status of an upload job.
	 * Only the job owner or an admin can view the job status.
	 *
	 * @param jobId The ID of the upload job
	 * @param userId The ID of the user making the request
	 * @return true if the user can view the job, false otherwise
	 */
	public static boolean canUserSeeUploadJob(long jobId, int userId) {
		UploadJob job = UploadJobQueue.getJob(jobId).orElse(null);
		if (job == null) {
			return false;
		}
		return job.getUserId() == userId || GeneralSecurity.hasAdminReadPrivileges(userId);
	}

	/**
	 * Checks whether a user may mutate an upload job (cancel/retry).
	 * Owners can manage their own jobs; administrators need write privileges.
	 */
	public static boolean canUserManageUploadJob(long jobId, int userId) {
		UploadJob job = UploadJobQueue.getJob(jobId).orElse(null);
		if (job == null) {
			return false;
		}
		return job.getUserId() == userId || GeneralSecurity.hasAdminWritePrivileges(userId);
	}
}
