package org.starexec.test.junit.database;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * No function or procedure is created by more than one repeatable migration.
 *
 * <p>Flyway re-runs a repeatable file only when its own checksum changes, so two files defining
 * the same routine leave the database with whichever definition ran last, and that depends on
 * which file was edited most recently, not on the file contents. {@code R__functions.sql} and
 * {@code R__procedures_and_views.sql} once both defined six status functions with different return
 * types. Needs no database.
 */
public class RepeatableMigrationsDefineEachFunctionOnceTest {

	private static final Pattern CREATE_ROUTINE = Pattern.compile(
			"CREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:FUNCTION|PROCEDURE)\\s+(?:starexec\\.)?\"?(\\w+)\"?\\s*\\(",
			Pattern.CASE_INSENSITIVE);

	@Test
	public void noRoutineIsDefinedInTwoRepeatableFiles() throws IOException, URISyntaxException {
		File dir = new File(getClass().getResource("/db/migration").toURI());
		File[] files = dir.listFiles((d, name) -> name.startsWith("R__") && name.endsWith(".sql"));
		assertNotNull(files);
		assertTrue("expected at least R__functions.sql and R__procedures_and_views.sql", files.length >= 2);

		Map<String, List<String>> definedIn = new HashMap<>();
		for (File f : files) {
			String sql = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
			// Comments may mention routines by name; only real statements count.
			StringBuilder code = new StringBuilder();
			for (String line : sql.split("\n")) {
				if (!line.trim().startsWith("--")) {
					code.append(line).append('\n');
				}
			}
			Matcher m = CREATE_ROUTINE.matcher(code);
			while (m.find()) {
				List<String> owners = definedIn.computeIfAbsent(m.group(1).toLowerCase(), k -> new ArrayList<>());
				if (!owners.contains(f.getName())) {
					owners.add(f.getName());
				}
			}
		}
		assertTrue("the scan must find the routines it is about", definedIn.containsKey("getjobstatus"));

		List<String> duplicated = new ArrayList<>();
		for (Map.Entry<String, List<String>> e : definedIn.entrySet()) {
			if (e.getValue().size() > 1) {
				duplicated.add(e.getKey() + " in " + e.getValue());
			}
		}
		java.util.Collections.sort(duplicated);
		assertEquals("routines defined by more than one repeatable migration", new ArrayList<String>(), duplicated);
	}
}
