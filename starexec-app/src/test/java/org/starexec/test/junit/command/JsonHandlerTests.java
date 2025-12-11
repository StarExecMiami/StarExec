package org.starexec.test.junit.command;

import com.google.gson.JsonElement;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.starexec.command.JsonHandler;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class JsonHandlerTests {

	@Test
	void testGetJsonString() throws Exception {
		HttpResponse response = Mockito.mock(HttpResponse.class);
		HttpEntity mockEntity = Mockito.mock(HttpEntity.class);
		Mockito.when(response.getEntity()).thenReturn(mockEntity);
		Mockito.when(mockEntity.getContent()).thenReturn(new ByteArrayInputStream("hello!".getBytes("UTF-8")));
		JsonElement e = JsonHandler.getJsonString(response);
		assertEquals("\"hello!\"", e.toString());
	}
}
