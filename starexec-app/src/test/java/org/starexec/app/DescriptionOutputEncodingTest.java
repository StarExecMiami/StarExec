package org.starexec.app;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.apache.taglibs.standard.functions.Functions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.command.HTMLParser;
import org.starexec.data.database.Users;
import org.starexec.data.to.Benchmark;
import org.starexec.data.to.Processor;
import org.starexec.data.to.Solver;
import org.starexec.data.to.Space;
import org.starexec.data.to.User;
import org.starexec.util.DataTablesQuery;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DescriptionOutputEncodingTest {
	private static final Path PUBLIC_JOB_JSP = Path.of("src/main/webapp/public/jobs/job.jsp");
	private static final Path SECURE_JOB_JSP = Path.of("src/main/webapp/secure/details/job.jsp");
	private static final Path EDIT_SPACE_JSP = Path.of("src/main/webapp/secure/edit/space.jsp");
	private static final Path EDIT_COMMUNITY_JS = Path.of("src/main/webapp/js/edit/community.js");
	private static final Path JOB_DETAILS_JS = Path.of("src/main/webapp/js/details/job.js");

	@Test
	public void spaceDataTableEncodesDescriptionsForHtmlRendering() {
		Space space = new Space();
		space.setId(17);
		space.setName("Safe space");
		space.setDescription("<script>stored-xss</script>");

		JsonObject response = RESTHelpers.convertSpacesToJsonObject(List.of(space), new DataTablesQuery());
		JsonArray row = response.getAsJsonArray("aaData").get(0).getAsJsonArray();
		String renderedDescription = row.get(1).getAsString();

		assertFalse(renderedDescription.contains("<script>"));
		assertTrue(renderedDescription.contains("&lt;script&gt;"));
	}

	@Test
	public void jspDescriptionSinksUseXmlEscapingForTextAndTextareaContent() throws Exception {
		assertEquals(
			"&lt;script&gt;stored-xss&lt;/script&gt;",
			Functions.escapeXml("<script>stored-xss</script>")
		);

		String publicJobJsp = Files.readString(PUBLIC_JOB_JSP);
		assertTrue(publicJobJsp.contains("<td><c:out value=\"${job.description}\"/></td>"));

		String secureJobJsp = Files.readString(SECURE_JOB_JSP);
		assertTrue(secureJobJsp.contains(
			"<span id=\"jobDescriptionText\"><c:out value=\"${job.description}\"/></span>"
		));
		assertTrue(secureJobJsp.contains(
			"<textarea id=\"editJobDescription\"><c:out value=\"${job.description}\"/></textarea>"
		));

		String editSpaceJsp = Files.readString(EDIT_SPACE_JSP);
		assertTrue(editSpaceJsp.contains(
			"length=\"${descLength}\"><c:out value=\"${space.description}\"/></textarea>"
		));
	}

	@Test
	public void clientDescriptionEditingUsesTextSafeDomApis() throws Exception {
		String communityJavaScript = Files.readString(EDIT_COMMUNITY_JS);
		assertTrue(communityJavaScript.contains("new RegExp(getPrimDescRegex()).test(newVal)"));
		assertTrue(communityJavaScript.contains(".text(value)"));
		assertFalse(communityJavaScript.contains("'<td id=\"edit' + attr + '\">' + newVal + '</td>'"));

		String jobJavaScript = Files.readString(JOB_DETAILS_JS);
		assertTrue(jobJavaScript.contains("$(textSelector).text(name);"));
		assertFalse(jobJavaScript.contains("$(textSelector).html(name);"));
	}

	@Test
	public void jobDescriptionClientPostsTheValueAsFormData() throws Exception {
		String jobJavaScript = Files.readString(JOB_DETAILS_JS);

		assertTrue(jobJavaScript.contains("postData = {description: name};"));
		assertFalse(jobJavaScript.contains(
			"'services/job/edit/' + nameOrDescription + '/' + jobId + '/' + name"
		));
	}

	@Test
	public void solverBenchmarkAndUserTablesEscapeHtmlWithoutBreakingCliParser() {
		Solver solver = new Solver();
		solver.setId(21);
		solver.setName("solver+1");
		solver.setDescription("<script>xss</script>");
		solver.setType(Solver.ExecutableType.SOLVER);

		JsonObject solverJson = RESTHelpers.convertSolversToJsonObject(List.of(solver), new DataTablesQuery());
		JsonArray solverRow = solverJson.getAsJsonArray("aaData").get(0).getAsJsonArray();
		assertFalse(solverRow.get(1).getAsString().contains("<script>"));
		assertTrue(solverRow.get(1).getAsString().contains("&lt;script&gt;"));
		assertEquals("solver+1", HTMLParser.extractNameFromJson(solverRow.get(0).getAsString(), "solvers"));

		Processor proc = new Processor();
		proc.setName("proc<1>");
		proc.setDescription("\" onmouseover=\"alert(1)");
		Benchmark bench = new Benchmark();
		bench.setId(31);
		bench.setName("bench.smt2");
		bench.setDescription("\" onclick=\"alert(1)");
		bench.setType(proc);

		JsonObject benchJson = RESTHelpers.convertBenchmarksToJsonObject(List.of(bench), new DataTablesQuery());
		JsonArray benchRow = benchJson.getAsJsonArray("aaData").get(0).getAsJsonArray();
		String benchCell = benchRow.get(0).getAsString();
		String typeCell = benchRow.get(1).getAsString();
		assertFalse(benchCell.contains("onclick=\"alert"));
		assertFalse(typeCell.contains("onmouseover=\"alert"));
		assertFalse(typeCell.contains("proc<1>"));
		assertEquals(Integer.valueOf(31), HTMLParser.extractIDFromJson(benchCell));
		assertEquals("bench.smt2", HTMLParser.extractNameFromJson(benchCell, "benchmarks"));

		User user = new User();
		user.setId(41);
		user.setFirstName("Ada");
		user.setLastName("Lovelace");
		user.setEmail("ada@example.org");
		user.setInstitution("<img src=x onerror=alert(1)>");
		JsonObject userJson;
		try (MockedStatic<Users> users = Mockito.mockStatic(Users.class)) {
			users.when(() -> Users.isAdmin(41)).thenReturn(false);
			users.when(() -> Users.isUnauthorized(41)).thenReturn(false);
			users.when(() -> Users.isSuspended(41)).thenReturn(false);
			users.when(() -> Users.isNormalUser(41)).thenReturn(true);
			users.when(() -> Users.isDeveloper(41)).thenReturn(false);
			userJson = RESTHelpers.convertUsersToJsonObject(List.of(user), new DataTablesQuery(), 41);
		}
		JsonArray userRow = userJson.getAsJsonArray("aaData").get(0).getAsJsonArray();
		assertFalse(userRow.get(1).getAsString().contains("<img"));
		assertTrue(userRow.get(1).getAsString().contains("&lt;img"));
	}

	@Test
	public void publicJobJspEscapesHeaderAndProcessorFields() throws Exception {
		String publicJobJsp = Files.readString(PUBLIC_JOB_JSP);
		assertTrue(publicJobJsp.contains("title=\"${fn:escapeXml(job.name)}\""));
		assertTrue(publicJobJsp.contains("<c:out value=\"${job.name}\"/>"));
		assertTrue(publicJobJsp.contains("<c:out value=\"${firstPostProc.name}\"/>"));
		assertTrue(publicJobJsp.contains("<c:out value=\"${job.queue.name}\"/>"));
	}
}
