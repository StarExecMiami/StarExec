package org.starexec.app;

import com.google.gson.Gson;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.constants.R;
import org.starexec.data.database.Users;
import org.starexec.data.security.PictureSecurity;
import org.starexec.data.security.ValidatorStatusCode;
import org.starexec.data.to.User;
import org.starexec.util.SessionUtil;

import javax.servlet.http.HttpServletRequest;
import java.nio.file.Files;
import java.nio.file.Path;

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

	// ----------------------------------------------------------------- harness

	/** Pictures for users 7 and 8, and the default. */
	private Path pictureDir() throws Exception {
		Path pictures = folder.newFolder("pictures").toPath();
		Files.createDirectories(pictures.resolve("users"));
		for (String name : new String[] { "Pic7_org.jpg", "Pic7_thn.jpg", "Pic8_org.jpg", "Pic8_thn.jpg", "Pic0.jpg" }) {
			Files.write(pictures.resolve("users").resolve(name), name.getBytes());
		}
		return pictures;
	}

	private ValidatorStatusCode delete(Path pictures, String type, int id, boolean allowed, boolean exists) {
		HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
		try (MockedStatic<SessionUtil> session = Mockito.mockStatic(SessionUtil.class);
				MockedStatic<PictureSecurity> security = Mockito.mockStatic(PictureSecurity.class);
				MockedStatic<Users> users = Mockito.mockStatic(Users.class);
				MockedStatic<R> r = Mockito.mockStatic(R.class, Mockito.CALLS_REAL_METHODS)) {
			session.when(() -> SessionUtil.getUserId(request)).thenReturn(CALLER);
			security.when(() -> PictureSecurity.canChangePicture(type, id, CALLER)).thenReturn(allowed);
			users.when(() -> Users.get(id)).thenReturn(exists ? new User() : null);
			r.when(R::getPicturePath).thenReturn(pictures.toString());
			return gson.fromJson(new RESTServices().deletePicture(type, id, request), ValidatorStatusCode.class);
		}
	}
}
