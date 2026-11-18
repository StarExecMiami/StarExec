package org.starexec.test.unit.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DescriptionDocumentationPolicyTest {

	@Test
	public void documentationAndLegacyTestsDescribeTheCurrentPolicy() throws Exception {
		String validator = read("src/main/java/org/starexec/util/Validator.java");
		assertTrue(validator.contains("Descriptions may be empty and may contain any characters except"));
		assertFalse(validator.contains("ALL characters are allowed in descriptions"));

		String legacyTests = read("src/test/java/org/starexec/test/integration/security/ValidatorTests.java");
		assertTrue(legacyTests.contains("Assertions.assertTrue(Validator.isValidPrimDescription(\"2017-05-22\"))"));
		assertTrue(legacyTests.contains("Assertions.assertTrue(Validator.isValidPrimDescription(\"C++\"))"));

		String restrictions = read("src/main/webapp/secure/help/input-restrictions.help");
		assertTrue(restrictions.contains("<code>&lt; &gt; &quot; ' % ; ) ( &amp;</code>"));
		assertFalse(restrictions.contains("&amp; \\ + -</code>"));
		assertTrue(restrictions.contains("{0,1024}$\"</code>"));
	}

	private String read(String relativePath) throws Exception {
		return Files.readString(Path.of(relativePath));
	}
}
