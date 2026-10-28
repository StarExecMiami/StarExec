package org.starexec.app;

import com.google.gson.Gson;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.constants.R;
import org.starexec.data.database.Benchmarks;
import org.starexec.data.database.Solvers;
import org.starexec.data.database.Users;
import org.starexec.data.security.GeneralSecurity;
import org.starexec.data.security.PictureSecurity;
import org.starexec.data.security.ValidatorStatusCode;
import org.starexec.data.to.Benchmark;
import org.starexec.data.to.Solver;
import org.starexec.data.to.User;
import org.starexec.util.SessionUtil;

import javax.servlet.http.HttpServletRequest;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@code POST /services/delete/picture/{type}/{id}}: a picture can be removed by whoever may
 * upload it, only the entity's own upload goes, and the default images are never touched.
 */
public class DeletePictureTest {

	private static final int CALLER = 7;

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private final Gson gson = new Gson();

	@Test
	public void removesTheOriginalAndTheThumbnailAndNothingElse() throws Exception {
		Path pictures = pictureDir();

		ValidatorStatusCode status = delete(pictures, "user", CALLER, true, true);

		assertTrue(status.getMessage(), status.isSuccess());
		assertFalse(Files.exists(pictures.resolve("users/Pic7_org.jpg")));
		assertFalse(Files.exists(pictures.resolve("users/Pic7_thn.jpg")));
		assertTrue("another user's picture stays", Files.exists(pictures.resolve("users/Pic8_org.jpg")));
		assertTrue("the default image stays", Files.exists(pictures.resolve("users/Pic0.jpg")));
	}

	@Test
	public void aCallerWhoMayNotChangeThePictureRemovesNothing() throws Exception {
		Path pictures = pictureDir();

		ValidatorStatusCode status = delete(pictures, "user", CALLER, false, true);

		assertFalse(status.isSuccess());
		assertTrue(Files.exists(pictures.resolve("users/Pic7_org.jpg")));
	}

	@Test
	public void anIdThatNamesNoEntityIsRefused() throws Exception {
		Path pictures = pictureDir();

		ValidatorStatusCode status = delete(pictures, "user", CALLER, true, false);

		assertFalse(status.isSuccess());
		assertTrue(Files.exists(pictures.resolve("users/Pic7_org.jpg")));
	}

	@Test
	public void aSolverIdThatNamesNoSolverIsRefused() throws Exception {
		Path pictures = pictureDir();

		ValidatorStatusCode status = delete(pictures, "solver", 3, true, false);

		assertFalse(status.isSuccess());
	}

	@Test
	public void aBenchmarkIdThatNamesNoBenchmarkIsRefused() throws Exception {
		Path pictures = pictureDir();

		ValidatorStatusCode status = delete(pictures, "benchmark", 4, true, false);

		assertFalse(status.isSuccess());
	}

	@Test
	public void anExistingSolversPictureIsRemoved() throws Exception {
		Path pictures = pictureDir();
		Files.createDirectories(pictures.resolve("solvers"));
		Files.write(pictures.resolve("solvers/Pic3_org.jpg"), new byte[] { 1 });

		ValidatorStatusCode status = delete(pictures, "solver", 3, true, true);

		assertTrue(status.getMessage(), status.isSuccess());
		assertFalse(Files.exists(pictures.resolve("solvers/Pic3_org.jpg")));
	}

	/** Id 0 would name the default image's neighbours; no entity has it, and it is refused first. */
	@Test
	public void theDefaultIdIsRefusedEvenToACallerAllowedEverything() throws Exception {
		Path pictures = pictureDir();

		ValidatorStatusCode status = delete(pictures, "user", 0, true, true);

		assertFalse(status.isSuccess());
		assertTrue(Files.exists(pictures.resolve("users/Pic0.jpg")));
	}

	@Test
	public void removingAPictureThatDoesNotExistSucceeds() throws Exception {
		Path pictures = folder.newFolder("empty").toPath();

		ValidatorStatusCode status = delete(pictures, "user", CALLER, true, true);

		assertTrue(status.getMessage(), status.isSuccess());
	}

	/**
	 * A type this endpoint does not know is refused rather than looked up as a benchmark.
	 * {@code canChangePicture} already refuses every type but the three, so this is only
	 * reachable if a fourth is ever added -- which is exactly when guessing the entity would
	 * check the wrong table.
	 */
	@Test
	public void anUnknownTypeIsRefusedRatherThanTreatedAsABenchmark() throws Exception {
		Path pictures = pictureDir();

		ValidatorStatusCode status = delete(pictures, "space", CALLER, true, true);

		assertFalse(status.isSuccess());
		assertEquals("Unknown picture type", status.getMessage());
	}

	/**
	 * What the caller is told, for each combination of the two files. The distinction that
	 * matters is between a partial removal and none at all: a file that was not there
	 * deletes silently and is not a deletion, so counting only the failures reported
	 * "partly removed" for a case where nothing had been removed.
	 */
	@Test
	public void oneFileRemovedAndOneThatWouldNotIsAPartialRemoval() throws Exception {
		Path pictures = pictureDir();
		undeletable(pictures.resolve("users/Pic7_thn.jpg"));

		ValidatorStatusCode status = delete(pictures, "user", CALLER, true, true);

		assertFalse(status.isSuccess());
		assertTrue("must say it was partial: " + status.getMessage(), status.getMessage().contains("partly"));
		assertFalse("what could be removed is gone", Files.exists(pictures.resolve("users/Pic7_org.jpg")));
	}

	@Test
	public void anAbsentFileAndOneThatWouldNotDeleteIsNotAPartialRemoval() throws Exception {
		Path pictures = pictureDir();
		Files.delete(pictures.resolve("users/Pic7_org.jpg"));
		undeletable(pictures.resolve("users/Pic7_thn.jpg"));

		ValidatorStatusCode status = delete(pictures, "user", CALLER, true, true);

		assertFalse(status.isSuccess());
		assertEquals("nothing was removed, so it must not claim a partial removal",
				"The picture could not be removed", status.getMessage());
	}

	@Test
	public void neitherFileDeletableIsReportedAsNoRemoval() throws Exception {
		Path pictures = pictureDir();
		undeletable(pictures.resolve("users/Pic7_org.jpg"));
		undeletable(pictures.resolve("users/Pic7_thn.jpg"));

		ValidatorStatusCode status = delete(pictures, "user", CALLER, true, true);

		assertFalse(status.isSuccess());
		assertEquals("The picture could not be removed", status.getMessage());
	}

	/**
	 * The junction between the endpoint and the rule, with nothing stubbed in between. Every
	 * other case here stubs {@link PictureSecurity}, so breaking the real rule leaves them all
	 * green and only its own test fails; these two run the real rule through the endpoint, so
	 * the wiring -- that the endpoint asks about the type and id it is about to delete, and
	 * obeys the answer -- is covered too.
	 */
	@Test
	public void theRealRuleRefusesAnotherUsersPicture() throws Exception {
		Path pictures = pictureDir();

		ValidatorStatusCode status = deleteUnderTheRealRule(pictures, "user", 8);

		assertFalse(status.isSuccess());
		assertTrue("the other user's picture stays", Files.exists(pictures.resolve("users/Pic8_org.jpg")));
	}

	@Test
	public void theRealRuleAllowsTheOwnersOwnPicture() throws Exception {
		Path pictures = pictureDir();

		ValidatorStatusCode status = deleteUnderTheRealRule(pictures, "user", CALLER);

		assertTrue(status.getMessage(), status.isSuccess());
		assertFalse(Files.exists(pictures.resolve("users/Pic7_org.jpg")));
	}

	// ----------------------------------------------------------------- harness

	/**
	 * Replaces a picture with something {@code deleteIfExists} refuses to remove: a directory
	 * that is not empty. The real failure, rather than a mocked filesystem.
	 */
	private static void undeletable(Path picture) throws Exception {
		Files.deleteIfExists(picture);
		Files.createDirectory(picture);
		Files.write(picture.resolve("occupied"), new byte[] { 1 });
	}

	/** Pictures for users 7 and 8, and the default. */
	private Path pictureDir() throws Exception {
		Path pictures = folder.newFolder("pictures").toPath();
		Files.createDirectories(pictures.resolve("users"));
		for (String name : new String[] { "Pic7_org.jpg", "Pic7_thn.jpg", "Pic8_org.jpg", "Pic8_thn.jpg", "Pic0.jpg" }) {
			Files.write(pictures.resolve("users").resolve(name), name.getBytes());
		}
		return pictures;
	}

	/**
	 * As {@link #delete}, but with the real {@link PictureSecurity} deciding. Only what the
	 * rule itself reaches out to is stubbed: who is public, who is an administrator, and
	 * whether the entity exists.
	 */
	private ValidatorStatusCode deleteUnderTheRealRule(Path pictures, String type, int id) {
		HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
		try (MockedStatic<SessionUtil> session = Mockito.mockStatic(SessionUtil.class);
				MockedStatic<Users> users = Mockito.mockStatic(Users.class);
				MockedStatic<GeneralSecurity> general = Mockito.mockStatic(GeneralSecurity.class);
				MockedStatic<R> r = Mockito.mockStatic(R.class, Mockito.CALLS_REAL_METHODS)) {
			session.when(() -> SessionUtil.getUserId(request)).thenReturn(CALLER);
			users.when(() -> Users.isPublicUser(CALLER)).thenReturn(false);
			users.when(() -> Users.get(id)).thenReturn(new User());
			general.when(() -> GeneralSecurity.hasAdminWritePrivileges(CALLER)).thenReturn(false);
			r.when(R::getPicturePath).thenReturn(pictures.toString());
			return gson.fromJson(new RESTServices().deletePicture(type, id, request), ValidatorStatusCode.class);
		}
	}

	private ValidatorStatusCode delete(Path pictures, String type, int id, boolean allowed, boolean exists) {
		HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
		try (MockedStatic<SessionUtil> session = Mockito.mockStatic(SessionUtil.class);
				MockedStatic<PictureSecurity> security = Mockito.mockStatic(PictureSecurity.class);
				MockedStatic<Users> users = Mockito.mockStatic(Users.class);
				MockedStatic<Solvers> solvers = Mockito.mockStatic(Solvers.class);
				MockedStatic<Benchmarks> benchmarks = Mockito.mockStatic(Benchmarks.class);
				MockedStatic<R> r = Mockito.mockStatic(R.class, Mockito.CALLS_REAL_METHODS)) {
			session.when(() -> SessionUtil.getUserId(request)).thenReturn(CALLER);
			security.when(() -> PictureSecurity.canChangePicture(type, id, CALLER)).thenReturn(allowed);
			users.when(() -> Users.get(id)).thenReturn(exists && "user".equals(type) ? new User() : null);
			solvers.when(() -> Solvers.get(id)).thenReturn(exists && "solver".equals(type) ? new Solver() : null);
			benchmarks.when(() -> Benchmarks.get(id)).thenReturn(exists && "benchmark".equals(type) ? new Benchmark() : null);
			r.when(R::getPicturePath).thenReturn(pictures.toString());
			return gson.fromJson(new RESTServices().deletePicture(type, id, request), ValidatorStatusCode.class);
		}
	}
}
