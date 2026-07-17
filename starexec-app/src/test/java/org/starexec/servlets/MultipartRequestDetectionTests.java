package org.starexec.servlets;

import org.apache.commons.fileupload2.javax.JavaxServletFileUpload;
import org.junit.Test;
import org.mockito.Mockito;

import javax.servlet.http.HttpServletRequest;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MultipartRequestDetectionTests {

	@Test
	public void detectsMultipartFormDataRequests() {
		HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
		Mockito.when(request.getMethod()).thenReturn("POST");
		Mockito.when(request.getContentType()).thenReturn("multipart/form-data; boundary=upload-boundary");

		assertTrue(JavaxServletFileUpload.isMultipartContent(request));
	}

	@Test
	public void rejectsNonMultipartRequests() {
		HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
		Mockito.when(request.getMethod()).thenReturn("POST");
		Mockito.when(request.getContentType()).thenReturn("application/json");

		assertFalse(JavaxServletFileUpload.isMultipartContent(request));
	}
}
