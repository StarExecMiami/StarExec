package org.starexec.data.security;

import org.starexec.data.database.Benchmarks;
import org.starexec.data.database.Users;
import org.starexec.data.to.Benchmark;
import org.starexec.util.PictureFiles;

/**
 * Who may change or remove a picture. Upload and delete both ask here, so the two cannot
 * come to disagree about whose picture a caller may touch.
 */
public final class PictureSecurity {

	private PictureSecurity() {
	}

	/**
	 * Whether the caller may replace or remove the picture of the entity {@code type} and
	 * {@code primId} name.
	 *
	 * <p>The id means a user, a solver or a benchmark depending on {@code type}, and each is
	 * owned by someone different, so each needs its own ownership test. An unrecognised type
	 * is refused for every caller, administrators included: it names no picture.
	 */
	public static boolean canChangePicture(String type, int primId, int userIdOfCaller) {
		if (!PictureFiles.isKnownType(type)) {
			return false;
		}
		if (Users.isPublicUser(userIdOfCaller)) {
			return false;
		}
		if (GeneralSecurity.hasAdminWritePrivileges(userIdOfCaller)) {
			return true;
		}
		switch (type) {
			case PictureFiles.USER:
				return primId == userIdOfCaller;
			case PictureFiles.SOLVER:
				return SolverSecurity.userOwnsSolverOrIsAdmin(primId, userIdOfCaller);
			case PictureFiles.BENCHMARK:
				Benchmark bench = Benchmarks.get(primId);
				return bench != null && BenchmarkSecurity.userOwnsBenchOrIsAdmin(bench, userIdOfCaller);
			default:
				return false;
		}
	}
}
