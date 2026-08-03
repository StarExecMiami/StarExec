package org.starexec.app;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DescriptionLogRedactionTest {

	@Test
	public void descriptionWritePathsLogMetadataInsteadOfRawValues() throws Exception {
		String restServices = read("src/main/java/org/starexec/app/RESTServices.java");
		assertFalse(restServices.contains("newValue=\" + newValue"));
		assertFalse(restServices.contains("desc=\" + newDesc"));
		assertTrue(restServices.contains("descriptionLength="));

		String spaces = read("src/main/java/org/starexec/data/database/Spaces.java");
		assertFalse(spaces.contains("newDesc=%s"));
		assertFalse(spaces.contains("updated description to [%s]"));
		assertTrue(spaces.contains("descriptionLength=%d"));

		String addSpace = read("src/main/java/org/starexec/servlets/AddSpace.java");
		assertFalse(addSpace.contains("Space description: \" + desc"));
		assertFalse(addSpace.contains("Invalid description: \" + desc"));

		String batchUtil = read("src/main/java/org/starexec/util/BatchUtil.java");
		assertFalse(batchUtil.contains("spaceAttributes: \" + spaceAttributes"));
		assertFalse(batchUtil.contains("found a new element = \" + childNode.toString()"));
	}

	private String read(String relativePath) throws Exception {
		return Files.readString(Path.of(relativePath));
	}
}
