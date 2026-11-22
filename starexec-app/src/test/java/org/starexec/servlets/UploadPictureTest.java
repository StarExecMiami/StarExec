package org.starexec.servlets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.data.security.PictureSecurity;
import org.starexec.util.PartWrapper;
import org.starexec.util.PictureFiles;
import org.starexec.util.SessionUtil;
import org.starexec.util.Util;

import javax.imageio.ImageIO;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class UploadPictureTest {

	@Test
	public void invalidImageDoesNotOverwriteExistingPictureAndReturnsGeneric500(@TempDir Path tempDir)
			throws Exception {
		Path originalPath = tempDir.resolve("users/Pic5_org.jpg");
		Path thumbPath = tempDir.resolve("users/Pic5_thn.jpg");
		Files.createDirectories(originalPath.getParent());
		byte[] priorBytes = "valid-existing-image-bytes".getBytes(StandardCharsets.UTF_8);
		Files.write(originalPath, priorBytes);
		Files.write(thumbPath, priorBytes);

		PartWrapper badUpload = mock(PartWrapper.class);
		doAnswer(inv -> {
			File dest = inv.getArgument(0);
			Files.writeString(dest.toPath(), "not-an-image", StandardCharsets.UTF_8);
			return null;
		}).when(badUpload).write(any(File.class));

		HashMap<String, Object> form = new HashMap<>();
		form.put("Id", "5");
		form.put("type", "user");
		form.put("f", badUpload);

		HttpServletRequest request = mock(HttpServletRequest.class);
		HttpServletResponse response = mock(HttpServletResponse.class);

		try (MockedStatic<SessionUtil> session = Mockito.mockStatic(SessionUtil.class);
			 MockedStatic<Util> util = Mockito.mockStatic(Util.class);
			 MockedStatic<PictureSecurity> picSec = Mockito.mockStatic(PictureSecurity.class);
			 MockedStatic<PictureFiles> picFiles = Mockito.mockStatic(PictureFiles.class)) {
			session.when(() -> SessionUtil.getUserId(request)).thenReturn(5);
			util.when(() -> Util.parseMultipartRequest(request)).thenReturn(form);
			util.when(() -> Util.createEncodedCookie(any(), any()))
					.thenAnswer(inv -> new javax.servlet.http.Cookie(inv.getArgument(0), "msg"));
			util.when(() -> Util.docRoot(any())).thenAnswer(inv -> "/" + inv.getArgument(0));
			picSec.when(() -> PictureSecurity.canChangePicture("user", 5, 5)).thenReturn(true);
			picFiles.when(() -> PictureFiles.original("user", 5)).thenReturn(originalPath.toFile());
			picFiles.when(() -> PictureFiles.thumbnail("user", 5)).thenReturn(thumbPath.toFile());

			Method doPost = UploadPicture.class.getDeclaredMethod(
					"doPost", HttpServletRequest.class, HttpServletResponse.class);
			doPost.setAccessible(true);
			doPost.invoke(new UploadPicture(), request, response);

			verify(response).sendError(eq(HttpServletResponse.SC_INTERNAL_SERVER_ERROR), eq("Picture upload failed"));
			assertArrayEquals(priorBytes, Files.readAllBytes(originalPath));
			assertArrayEquals(priorBytes, Files.readAllBytes(thumbPath));
			assertEquals(2L, Files.list(originalPath.getParent()).count(), "Temporary files must be cleaned up");
		}
	}

	@Test
	public void validImageStagesAtomicallyAndWritesThumbnail(@TempDir Path tempDir) throws Exception {
		Path originalPath = tempDir.resolve("users/Pic6_org.jpg");
		Path thumbPath = tempDir.resolve("users/Pic6_thn.jpg");

		PartWrapper validUpload = mock(PartWrapper.class);
		doAnswer(inv -> {
			File dest = inv.getArgument(0);
			BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
			ImageIO.write(img, "PNG", dest);
			return null;
		}).when(validUpload).write(any(File.class));

		HashMap<String, Object> form = new HashMap<>();
		form.put("Id", "6");
		form.put("type", "user");
		form.put("f", validUpload);

		HttpServletRequest request = mock(HttpServletRequest.class);
		HttpServletResponse response = mock(HttpServletResponse.class);

		try (MockedStatic<SessionUtil> session = Mockito.mockStatic(SessionUtil.class);
			 MockedStatic<Util> util = Mockito.mockStatic(Util.class);
			 MockedStatic<PictureSecurity> picSec = Mockito.mockStatic(PictureSecurity.class);
			 MockedStatic<PictureFiles> picFiles = Mockito.mockStatic(PictureFiles.class)) {
			session.when(() -> SessionUtil.getUserId(request)).thenReturn(6);
			util.when(() -> Util.parseMultipartRequest(request)).thenReturn(form);
			util.when(() -> Util.docRoot(any())).thenAnswer(inv -> "/" + inv.getArgument(0));
			picSec.when(() -> PictureSecurity.canChangePicture("user", 6, 6)).thenReturn(true);
			picFiles.when(() -> PictureFiles.original("user", 6)).thenReturn(originalPath.toFile());
			picFiles.when(() -> PictureFiles.thumbnail("user", 6)).thenReturn(thumbPath.toFile());

			Method doPost = UploadPicture.class.getDeclaredMethod(
					"doPost", HttpServletRequest.class, HttpServletResponse.class);
			doPost.setAccessible(true);
			doPost.invoke(new UploadPicture(), request, response);

			verify(response).sendRedirect("/secure/edit/account.jsp");
			assertTrue(Files.isRegularFile(originalPath));
			assertTrue(Files.isRegularFile(thumbPath));
			assertEquals(2L, Files.list(originalPath.getParent()).count(), "Temporary files must be cleaned up");
		}
	}

	@Test
	public void outerExceptionSends500InsteadOfSilent200() throws Exception {
		HttpServletRequest request = mock(HttpServletRequest.class);
		HttpServletResponse response = mock(HttpServletResponse.class);
		when(response.isCommitted()).thenReturn(false);

		try (MockedStatic<SessionUtil> session = Mockito.mockStatic(SessionUtil.class);
			 MockedStatic<Util> util = Mockito.mockStatic(Util.class)) {
			session.when(() -> SessionUtil.getUserId(request)).thenReturn(7);
			util.when(() -> Util.parseMultipartRequest(request)).thenThrow(new RuntimeException("secret path /var/x"));

			Method doPost = UploadPicture.class.getDeclaredMethod(
					"doPost", HttpServletRequest.class, HttpServletResponse.class);
			doPost.setAccessible(true);
			doPost.invoke(new UploadPicture(), request, response);

			verify(response).sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Picture upload failed");
		}
	}
}
