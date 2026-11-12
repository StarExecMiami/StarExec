package org.starexec.servlets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.starexec.test.TemporaryFolders.newFolder;

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

	@TempDir
	public File folder;

	/** The Kubernetes layout: a data volume with no defaults on it. */
	@Test
	public void servesTheBundledDefaultWhenTheDataVolumeHasNone() throws Exception {
		Path pictures = newFolder(folder, "pictures").toPath();

		Served served = get(pictures, "uorg", "5");

		assertEquals(0, served.errorStatus, "no error status");
		assertArrayEquals(bundled(BUNDLED_USER_DEFAULT), served.body, "the bundled default picture");
		assertTrue(served.body.length > 0, "and not an empty body");
		assertEquals("image/jpeg", served.contentType);
		assertEquals("image/jpeg", served.contentTypeAtFirstByte);
	}

	/** The same for benchmarks, the third kind of picture with a bundled default. */
	@Test
	public void servesTheBundledBenchmarkDefaultWhenTheDataVolumeHasNone() throws Exception {
		Path pictures = newFolder(folder, "pictures").toPath();

		Served served = get(pictures, "bthn", "3");

		assertEquals(0, served.errorStatus);
		assertArrayEquals(bundled("static/default-pics/default-pics/benchmarks/Pic0.jpg"), served.body);
		assertEquals("image/jpeg", served.contentType);
	}

	/** The Local and Podman layout: the data volume has the defaults, and they still win. */
	@Test
	public void aDefaultOnTheDataVolumeIsStillServedFirst() throws Exception {
		Path pictures = newFolder(folder, "pictures").toPath();
		byte[] onVolume = "default on the volume".getBytes();
		write(pictures.resolve("users/Pic0.jpg"), onVolume);

		Served served = get(pictures, "uthn", "5");

		assertEquals(0, served.errorStatus);
		assertArrayEquals(onVolume, served.body);
	}

	/** An entity's own picture is served whatever the defaults. */
	@Test
	public void anUploadedPictureIsServed() throws Exception {
		Path pictures = newFolder(folder, "pictures").toPath();
		byte[] uploaded = "uploaded picture".getBytes();
		write(pictures.resolve("solvers/Pic7_org.jpg"), uploaded);

		Served served = get(pictures, "sorg", "7");

		assertEquals(0, served.errorStatus);
		assertArrayEquals(uploaded, served.body);
	}

	/**
	 * The id names a primitive, not a file name, so a non-canonical spelling of it names the
	 * same primitive. Before the id was parsed, "007" built Pic007_org.jpg, found nothing and
	 * served the default -- the picture of a user who exists, reported as a user with none.
	 */
	@Test
	public void aNonCanonicalIdNamesTheSamePicture() throws Exception {
		Path pictures = newFolder(folder, "pictures").toPath();
		byte[] uploaded = "uploaded picture".getBytes();
		write(pictures.resolve("solvers/Pic7_org.jpg"), uploaded);

		Served served = get(pictures, "sorg", "007");

		assertEquals(0, served.errorStatus);
		assertArrayEquals(uploaded, served.body);
	}

	/** Result charts have no default: a missing one is an error, not an empty success. */
	@Test
	public void aMissingPictureWithNoDefaultIsNotFound() throws Exception {
		Path pictures = newFolder(folder, "pictures").toPath();

		Served served = get(pictures, "corg", "9");

		assertEquals(HttpServletResponse.SC_NOT_FOUND, served.errorStatus);
		assertEquals(0, served.body.length);
	}

	@Test
	public void pngOriginalUsesItsContentTypeDespiteTheJpgStorageName() throws Exception {
		Path pictures = newFolder(folder, "pictures").toPath();
		ByteArrayOutputStream encoded = new ByteArrayOutputStream();
		assertTrue(ImageIO.write(new BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB), "png", encoded));
		byte[] png = encoded.toByteArray();
		write(pictures.resolve("users/Pic7_org.jpg"), png);

		Served served = get(pictures, "uorg", "7");

		assertEquals("image/png", served.contentType);
		assertEquals("image/png", served.contentTypeAtFirstByte);
		assertArrayEquals(png, served.body, "type detection must not consume image bytes");
	}

	@Test
	public void jpegStoredOnDiskHasAnImageMediaType() throws Exception {
		Path pictures = newFolder(folder, "pictures").toPath();
		byte[] jpeg = bundled(BUNDLED_USER_DEFAULT);
		write(pictures.resolve("users/Pic7_thn.jpg"), jpeg);

		Served served = get(pictures, "uthn", "7");

		assertEquals("image/jpeg", served.contentType);
		assertArrayEquals(jpeg, served.body);
	}

	@Test
	public void nonImageBytesAreNotServedAsActiveContent() throws Exception {
		Path pictures = newFolder(folder, "pictures").toPath();
		byte[] html = "<html><body>not an image</body></html>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		write(pictures.resolve("users/Pic7_org.jpg"), html);

		Served served = get(pictures, "uorg", "7");

		assertEquals("application/octet-stream", served.contentType);
		assertArrayEquals(html, served.body);
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
				if (body.size() == 0) {
					served.contentTypeAtFirstByte = served.contentType;
				}
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
			served.contentType = call.getArgument(0);
			return null;
		}).when(response).setContentType(Mockito.anyString());
		Mockito.doAnswer(call -> {
			served.errorStatus = call.getArgument(0);
			return null;
		}).when(response).sendError(Mockito.anyInt(), Mockito.anyString());
		Mockito.doAnswer(call -> {
			served.errorStatus = call.getArgument(0);
			return null;
		}).when(response).sendError(Mockito.anyInt());

		try (MockedStatic<R> r = Mockito.mockStatic(R.class, Mockito.CALLS_REAL_METHODS)) {
			r.when(R::getPicturePath).thenReturn(pictures.toString());
			new GetPicture().doGet(request, response);
		}
		served.body = body.toByteArray();
		return served;
	}

	private static byte[] bundled(String resource) throws Exception {
		try (InputStream in = GetPicture.class.getClassLoader().getResourceAsStream(resource)) {
			assertTrue(in != null, "the default must be on the classpath: " + resource);
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
		String contentType;
		String contentTypeAtFirstByte;
	}
}
