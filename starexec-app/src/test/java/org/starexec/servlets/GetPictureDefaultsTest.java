package org.starexec.servlets;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.constants.R;

import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Which bytes {@link GetPicture} serves when an entity has no picture of its own.
 *
 * <p>The defaults ship inside the application and the Dockerfile also copies them into the
 * image's data directory. Podman copies that into a new named volume, so Local and Podman have
 * them; a Kubernetes volume is mounted over the directory and hides them, and the servlet
 * answered 200 with an empty body (reproduced on microk8s: every default picture was zero bytes).
 */
public class GetPictureDefaultsTest {

	private static final String BUNDLED_USER_DEFAULT = "static/default-pics/default-pics/users/Pic0.jpg";

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	/** The Kubernetes layout: a data volume with no defaults on it. */
	@Test
	public void servesTheBundledDefaultWhenTheDataVolumeHasNone() throws Exception {
		Path pictures = folder.newFolder("pictures").toPath();

		Served served = get(pictures, "uorg", "5");

		assertEquals("no error status", 0, served.errorStatus);
		assertArrayEquals("the bundled default picture", bundled(BUNDLED_USER_DEFAULT), served.body);
		assertTrue("and not an empty body", served.body.length > 0);
	}

	/** The Local and Podman layout: the data volume has the defaults, and they still win. */
	@Test
	public void aDefaultOnTheDataVolumeIsStillServedFirst() throws Exception {
		Path pictures = folder.newFolder("pictures").toPath();
		byte[] onVolume = "default on the volume".getBytes();
		write(pictures.resolve("users/Pic0.jpg"), onVolume);

		Served served = get(pictures, "uthn", "5");

		assertEquals(0, served.errorStatus);
		assertArrayEquals(onVolume, served.body);
	}

	/** An entity's own picture is served whatever the defaults. */
	@Test
	public void anUploadedPictureIsServed() throws Exception {
		Path pictures = folder.newFolder("pictures").toPath();
		byte[] uploaded = "uploaded picture".getBytes();
		write(pictures.resolve("solvers/Pic7_org.jpg"), uploaded);

		Served served = get(pictures, "sorg", "7");

		assertEquals(0, served.errorStatus);
		assertArrayEquals(uploaded, served.body);
	}

	/** Result charts have no default: a missing one is an error, not an empty success. */
	@Test
	public void aMissingPictureWithNoDefaultIsNotFound() throws Exception {
		Path pictures = folder.newFolder("pictures").toPath();

		Served served = get(pictures, "corg", "9");

		assertEquals(HttpServletResponse.SC_NOT_FOUND, served.errorStatus);
		assertEquals(0, served.body.length);
	}

	// ----------------------------------------------------------------- harness

	private static Served get(Path pictures, String type, String id) throws Exception {
		HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
		Mockito.when(request.getParameter("type")).thenReturn(type);
		Mockito.when(request.getParameter("Id")).thenReturn(id);
		Mockito.when(request.getParameterMap()).thenReturn(java.util.Map.of(
				"type", new String[] { type }, "Id", new String[] { id }));

		Served served = new Served();
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		HttpServletResponse response = Mockito.mock(HttpServletResponse.class);
		Mockito.when(response.getOutputStream()).thenReturn(new ServletOutputStream() {
			@Override
			public void write(int b) {
				body.write(b);
			}

			@Override
			public boolean isReady() {
				return true;
			}

			@Override
			public void setWriteListener(WriteListener listener) {
			}
		});
		Mockito.doAnswer(call -> {
			served.errorStatus = call.getArgument(0);
			return null;
		}).when(response).sendError(Mockito.anyInt(), Mockito.anyString());

		try (MockedStatic<R> r = Mockito.mockStatic(R.class, Mockito.CALLS_REAL_METHODS)) {
			r.when(R::getPicturePath).thenReturn(pictures.toString());
			new GetPicture().doGet(request, response);
		}
		served.body = body.toByteArray();
		return served;
	}

	private static byte[] bundled(String resource) throws Exception {
		try (InputStream in = GetPicture.class.getClassLoader().getResourceAsStream(resource)) {
			assertTrue("the default must be on the classpath: " + resource, in != null);
			return in.readAllBytes();
		}
	}

	private static void write(Path file, byte[] bytes) throws Exception {
		Files.createDirectories(file.getParent());
		Files.write(file, bytes);
	}

	private static final class Served {
		int errorStatus;
		byte[] body;
	}
}
