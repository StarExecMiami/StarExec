package org.starexec.test.junit.data.security;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.data.database.Benchmarks;
import org.starexec.data.database.Users;
import org.starexec.data.security.BenchmarkSecurity;
import org.starexec.data.security.GeneralSecurity;
import org.starexec.data.security.PictureSecurity;
import org.starexec.data.security.SolverSecurity;
import org.starexec.data.to.Benchmark;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The one rule upload and delete both use for whose picture a caller may change. It replaces
 * AdminPictureUploadTest(s), which re-implemented the rule inside the test and asserted on
 * their own copy, so they exercised no production code.
 */
public class PictureSecurityTest {

	private static final int CALLER = 7;
	private static final int OTHER = 8;

	@Test
	public void aUserMayChangeTheirOwnPictureOnly() {
		try (Roles roles = new Roles(false, false)) {
			assertTrue(PictureSecurity.canChangePicture("user", CALLER, CALLER));
			assertFalse(PictureSecurity.canChangePicture("user", OTHER, CALLER));
		}
	}

	@Test
	public void anAdministratorMayChangeAnyPictureOfAKnownType() {
		try (Roles roles = new Roles(false, true)) {
			assertTrue(PictureSecurity.canChangePicture("user", OTHER, CALLER));
			assertTrue(PictureSecurity.canChangePicture("solver", 3, CALLER));
			assertTrue(PictureSecurity.canChangePicture("benchmark", 4, CALLER));
		}
	}

	/** An unknown type names no picture; upload used to write it at the picture root. */
	@Test
	public void anUnknownTypeIsRefusedEvenToAnAdministrator() {
		try (Roles roles = new Roles(false, true)) {
			assertFalse(PictureSecurity.canChangePicture("space", CALLER, CALLER));
			assertFalse(PictureSecurity.canChangePicture(null, CALLER, CALLER));
		}
	}

	@Test
	public void thePublicUserMayChangeNothing() {
		try (Roles roles = new Roles(true, true)) {
			assertFalse(PictureSecurity.canChangePicture("user", CALLER, CALLER));
		}
	}

	@Test
	public void solverPicturesFollowSolverOwnership() {
		try (Roles roles = new Roles(false, false);
				MockedStatic<SolverSecurity> solvers = Mockito.mockStatic(SolverSecurity.class)) {
			solvers.when(() -> SolverSecurity.userOwnsSolverOrIsAdmin(3, CALLER)).thenReturn(true);
			solvers.when(() -> SolverSecurity.userOwnsSolverOrIsAdmin(4, CALLER)).thenReturn(false);

			assertTrue(PictureSecurity.canChangePicture("solver", 3, CALLER));
			assertFalse(PictureSecurity.canChangePicture("solver", 4, CALLER));
		}
	}

	@Test
	public void benchmarkPicturesFollowBenchmarkOwnershipAndNeedTheBenchmark() {
		Benchmark owned = new Benchmark();
		try (Roles roles = new Roles(false, false);
				MockedStatic<Benchmarks> benchmarks = Mockito.mockStatic(Benchmarks.class);
				MockedStatic<BenchmarkSecurity> security = Mockito.mockStatic(BenchmarkSecurity.class)) {
			benchmarks.when(() -> Benchmarks.get(5)).thenReturn(owned);
			benchmarks.when(() -> Benchmarks.get(6)).thenReturn(null);
			security.when(() -> BenchmarkSecurity.userOwnsBenchOrIsAdmin(owned, CALLER)).thenReturn(true);

			assertTrue(PictureSecurity.canChangePicture("benchmark", 5, CALLER));
			assertFalse("a missing benchmark", PictureSecurity.canChangePicture("benchmark", 6, CALLER));
		}
	}

	/** Stubs the caller's standing: public user or not, administrator or not. */
	private static final class Roles implements AutoCloseable {
		private final MockedStatic<Users> users = Mockito.mockStatic(Users.class);
		private final MockedStatic<GeneralSecurity> general = Mockito.mockStatic(GeneralSecurity.class);

		Roles(boolean publicUser, boolean admin) {
			users.when(() -> Users.isPublicUser(CALLER)).thenReturn(publicUser);
			general.when(() -> GeneralSecurity.hasAdminWritePrivileges(CALLER)).thenReturn(admin);
		}

		@Override
		public void close() {
			general.close();
			users.close();
		}
	}
}
