package org.starexec.app;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.apache.taglibs.standard.functions.Functions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.command.HTMLParser;
import org.starexec.constants.R;
import org.starexec.data.database.AnonymousLinks.PrimitivesToAnonymize;
import org.starexec.data.database.Users;
import org.starexec.data.to.Benchmark;
import org.starexec.data.to.JobPair;
import org.starexec.data.to.Processor;
import org.starexec.data.to.Solver;
import org.starexec.data.to.Space;
import org.starexec.data.to.User;
import org.starexec.data.to.pipelines.JoblineStage;
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
	public void anonymousPairTableRendersStageResultsAsText() {
		Benchmark benchmark = new Benchmark();
		benchmark.setName("Benchmark 1");
		JobPair pair = new JobPair();
		pair.setBench(benchmark);
		JoblineStage stage = new JoblineStage();
		stage.setStageNumber(1);
		stage.getSolver().setName("Solver 1");
		String result = "<b>unsat & unknown</b>";
		stage.getAttributes().setProperty(R.STAREXEC_RESULT, result);
		pair.addStage(stage);

		JsonObject response = RESTHelpers.convertJobPairsToJsonObject(
				List.of(pair), new DataTablesQuery(), true, 1, PrimitivesToAnonymize.ALL);
		String cell = response.getAsJsonArray("aaData").get(0).getAsJsonArray().get(5).getAsString();

		assertFalse(cell.contains("<b>"));
		assertTrue(cell.contains("&lt;b&gt;unsat &amp; unknown"));
		assertEquals(result, stage.getStarexecResult());
	}

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
		assertTrue(jobJavaScript.contains("var $text = $(textSelector);"));
		assertTrue(jobJavaScript.contains("$text.text(value);"));
		assertFalse(jobJavaScript.contains("$text.html("));
		assertFalse(jobJavaScript.contains("$(textSelector).html("));
	}

	@Test
	public void jobDescriptionClientPostsTheValueAsFormData() throws Exception {
		String jobJavaScript = Files.readString(JOB_DETAILS_JS);

		assertTrue(jobJavaScript.contains("data.description = value;"));
		assertTrue(jobJavaScript.contains("$.post(url, data, null, 'json')"));
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

		Benchmark untypedBench = new Benchmark();
		untypedBench.setId(32);
		untypedBench.setName("untyped.smt2");
		untypedBench.setType(null);

		Processor blankType = Mockito.mock(Processor.class);
		Mockito.when(blankType.getName()).thenReturn(null);
		Mockito.when(blankType.getDescription()).thenReturn(null);
		Benchmark nullFieldTypeBench = new Benchmark();
		nullFieldTypeBench.setId(33);
		nullFieldTypeBench.setName("blank-type.smt2");
		nullFieldTypeBench.setType(blankType);

		JsonObject untypedJson = RESTHelpers.convertBenchmarksToJsonObject(
				List.of(bench, untypedBench, nullFieldTypeBench), new DataTablesQuery());
		JsonArray untypedRows = untypedJson.getAsJsonArray("aaData");
		assertEquals("<span title=\"\">none</span>", untypedRows.get(1).getAsJsonArray().get(1).getAsString());
		assertEquals("<span title=\"\">none</span>", untypedRows.get(2).getAsJsonArray().get(1).getAsString());
		assertTrue(untypedRows.get(0).getAsJsonArray().get(1).getAsString().contains("proc&lt;1&gt;"));

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
