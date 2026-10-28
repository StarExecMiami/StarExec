package org.starexec.data.database;

import com.google.gson.Gson;
import org.apache.commons.io.FileUtils;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.constants.R;
import org.starexec.data.to.UploadSession;
import org.starexec.data.to.UploadSessionCreateRequest;
import org.starexec.data.to.User;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * An upload session that is not created leaves no directory behind, including when its user is
 * deleted just before the session's directory is made (#275).
 *
 * <p>The database is replaced at its static boundary; the files are real, under a temporary
 * benchmark root. A user deletion is simulated the way Users.deleteUser ends: the user's row is
 * gone, so the insert fails on its foreign key, and the user's directory under the benchmark root
 * is deleted.
 */
public class UploadSessionCreateCleanupTest {

	private static final int OWNER = 5;
	private static final long SESSION_ID = 17L;

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private final AtomicBoolean ownerExists = new AtomicBoolean(true);
	private File root;
	private File neighbour;
	private boolean insertFails;
	/** Runs once, the first time the benchmark root is asked for: just before the directory is made. */
	private Runnable beforeDirectory = () -> { };

	@Before
	public void setUp() throws IOException {
		root = tmp.newFolder("Benchmarks");
		neighbour = new File(root, "6/20261018-10.00.00.000/other.smt2");
		Files.createDirectories(neighbour.getParentFile().toPath());
		Files.writeString(neighbour.toPath(), "(check-sat)\n");
	}

	@Test
	public void aSessionThatIsCreatedKeepsItsDirectory() throws Exception {
		Optional<UploadSession> session = create();

		assertTrue(session.isPresent());
		File[] dirs = ownerDirectories();
		assertEquals(1, dirs.length);
		File[] inside = dirs[0].listFiles();
		assertEquals(1, inside.length);
		assertTrue(inside[0].getName().startsWith("upload-session-"));
	}

	@Test
	public void aSessionThatIsNotInsertedLeavesNoDirectory() throws Exception {
		insertFails = true;

		Optional<UploadSession> session = create();

		assertFalse(session.isPresent());
		assertArrayEquals("no directory is left", new File[0], ownerDirectories());
	}

	/** The race from #275: the user is deleted just before the session's directory is made. */
	@Test
	public void aUserDeletedBeforeTheDirectoryIsMadeLeavesNothing() throws Exception {
		beforeDirectory = this::deleteOwner;

		Optional<UploadSession> session = create();

		assertFalse(session.isPresent());
		assertFalse("nothing is left under the deleted user's benchmark directory",
				new File(root, "" + OWNER).exists());
		assertTrue("another user's benchmark is untouched", neighbour.isFile());
	}

	/** Users.deleteUser's end: the row is gone and the user's benchmark directory is deleted. */
	private void deleteOwner() {
		ownerExists.set(false);
		try {
			FileUtils.deleteDirectory(new File(root, "" + OWNER));
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	private Optional<UploadSession> create() throws Exception {
		UploadSessionCreateRequest request = new Gson().fromJson(
				"{\"fileName\":\"bench.tgz\",\"totalBytes\":10,\"spaceId\":3,"
						+ "\"uploadMethod\":\"dump\",\"benchmarkTypeId\":1}",
				UploadSessionCreateRequest.class);
		User owner = new User();
		owner.setId(OWNER);

		Connection con = mock(Connection.class);
		PreparedStatement insert = mock(PreparedStatement.class);
		ResultSet keys = mock(ResultSet.class);
		when(con.prepareStatement(anyString(), anyInt())).thenReturn(insert);
		when(insert.executeUpdate()).thenAnswer(invocation -> {
			// upload_sessions.user_id references users: the insert fails once the row is gone.
			if (insertFails || !ownerExists.get()) {
				throw new SQLException("insert or update on table \"upload_sessions\" violates foreign key"
						+ " constraint", "23503");
			}
			return 1;
		});
		when(insert.getGeneratedKeys()).thenReturn(keys);
		when(keys.next()).thenReturn(true);
		when(keys.getLong(1)).thenReturn(SESSION_ID);
		UploadSession created = new UploadSession();
		created.setId(SESSION_ID);
		created.setUserId(OWNER);

		AtomicBoolean asked = new AtomicBoolean();
		try (MockedStatic<R> r = mockStatic(R.class, invocation -> {
			     if (invocation.getMethod().getName().equals("getBenchmarkPath")) {
				     if (!asked.getAndSet(true)) {
					     beforeDirectory.run();
				     }
				     return root.getAbsolutePath();
			     }
			     return invocation.callRealMethod();
		     });
		     MockedStatic<Users> users = mockStatic(Users.class, invocation -> {
			     switch (invocation.getMethod().getName()) {
			     case "get":
				     return ownerExists.get() ? owner : null;
			     case "exists":
				     return ownerExists.get();
			     default:
				     return Mockito.RETURNS_DEFAULTS.answer(invocation);
			     }
		     });
		     MockedStatic<Common> common = mockStatic(Common.class, invocation ->
				     invocation.getMethod().getName().equals("getConnection")
						     ? con : invocation.callRealMethod());
		     MockedStatic<UploadSessions> sessions = mockStatic(UploadSessions.class, invocation ->
				     invocation.getMethod().getName().equals("getSession")
						     ? Optional.of(created) : invocation.callRealMethod())) {
			return UploadSessions.createSession(OWNER, request);
		}
	}

	/** The directories under the owner's benchmark directory. */
	private File[] ownerDirectories() {
		File[] dirs = new File(root, "" + OWNER).listFiles();
		return dirs == null ? new File[0] : dirs;
	}
}
