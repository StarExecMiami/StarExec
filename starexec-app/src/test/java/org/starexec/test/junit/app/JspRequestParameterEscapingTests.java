package org.starexec.test.junit.app;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Static scan of JSP source (there is no JSP renderer in the unit-test classpath): request-derived values written
 * into HTML attributes must go through {@code c:out}, never a bare EL expression.
 */
public class JspRequestParameterEscapingTests {
	private static final Path WEBAPP = Paths.get("src/main/webapp/secure");

	private static String read(String name) throws IOException {
		return new String(Files.readAllBytes(WEBAPP.resolve(name)), StandardCharsets.UTF_8);
	}

	@Test
	public void solverDetailsEscapesContextSpaceId() throws IOException {
		String jsp = read("details/solver.jsp");
		assertFalse(jsp.contains("contextSpaceId=${param.contextSpaceId}"));
		assertTrue(jsp.contains("contextSpaceId=<c:out value=\"${param.contextSpaceId}\"/>"));
	}

	@Test
	public void benchmarkDetailsEscapesContextSpaceId() throws IOException {
		String jsp = read("details/benchmark.jsp");
		assertFalse(jsp.contains("contextSpaceId=${param.contextSpaceId}"));
		assertTrue(jsp.contains("contextSpaceId=<c:out value=\"${param.contextSpaceId}\"/>"));
	}

	@Test
	public void helpPageEscapesTheRefParameter() throws IOException {
		String jsp = read("help.jsp");
		assertFalse(jsp.contains("href=\"${ref}\""), "raw ref must not be written into markup");
		assertTrue(jsp.contains("<c:out value='${ref}'/>"));
		assertTrue(jsp.contains("prefix=\"c\""), "c taglib must be declared");
	}

	@Test
	public void spacePermissionsEscapesSpaceIdParameter() throws IOException {
		String jsp = read("edit/spacePermissions.jsp");
		assertFalse(jsp.contains("spaces.jsp?id=${param.id}"));
		assertTrue(jsp.contains("spaces.jsp?id=<c:out value='${param.id}'/>"));
	}

	@Test
	public void pairAndBenchmarkPagesEscapeAttributes() throws IOException {
		String pair = read("details/pair.jsp");
		assertFalse(pair.contains("<td>${entry.key}</td>"));
		assertFalse(pair.contains("<td>${entry.value}</td>"));
		assertTrue(pair.contains("<c:out value=\"${entry.key}\"/>"));
		assertTrue(pair.contains("<c:out value=\"${entry.value}\"/>"));

		String bench = read("details/benchmark.jsp");
		assertFalse(bench.contains("<td>${entry.key}</td>"));
		assertFalse(bench.contains("<td>${entry.value}</td>"));
		assertFalse(bench.contains("<td>${dependency.dependencyPath}</td>"));
		assertTrue(bench.contains("<c:out value=\"${dependency.dependencyPath}\"/>"));
	}

	@Test
	public void entityTagsEscapeNamesAndDescriptions() throws IOException {
		Path tagsDir = Paths.get("src/main/webapp/WEB-INF/tags");
		for (String tagName : new String[]{"solver.tag", "benchmark.tag", "config.tag"}) {
			String tag = new String(Files.readAllBytes(tagsDir.resolve(tagName)), StandardCharsets.UTF_8);
			assertTrue(tag.contains("fn:escapeXml(value.description)"), tagName + " must escape description");
			assertTrue(tag.contains("<c:out value=\"${value.name}\"/>"), tagName + " must escape name");
		}
		String userTag = new String(Files.readAllBytes(tagsDir.resolve("user.tag")), StandardCharsets.UTF_8);
		assertTrue(userTag.contains("<c:out value=\"${value.fullName}\"/>"));

		String communityTag = new String(Files.readAllBytes(tagsDir.resolve("community.tag")), StandardCharsets.UTF_8);
		assertTrue(communityTag.contains("<c:out value=\"${value.name}\"/>"));
	}

	@Test
	public void detailViewsEscapeEntityFields() throws IOException {
		String solver = read("details/solver.jsp");
		assertTrue(solver.contains("title=\"${fn:escapeXml(solverPageTitle)}\""));
		assertTrue(solver.contains("<c:out value=\"${solver.name}\"/>"));
		assertTrue(solver.contains("<c:out value=\"${solver.description}\"/>"));

		String user = read("details/user.jsp");
		assertTrue(user.contains("title=\"${fn:escapeXml(t_user.fullName)}\""));
		assertTrue(user.contains("<c:out value=\"${t_user.institution}\"/>"));

		String job = read("details/job.jsp");
		assertTrue(job.contains("<star:template title=\"${fn:escapeXml(pageTitle)}\""));
		assertTrue(job.contains("<span id=\"jobNameText\"><c:out value=\"${job.name}\"/></span>"));

		String matrix = new String(
				Files.readAllBytes(Paths.get("src/main/webapp/WEB-INF/secure/details/jobMatrixView.jsp")),
				StandardCharsets.UTF_8
		);
		assertTrue(matrix.contains("title=\"${fn:escapeXml(job.name)}\""));
		assertTrue(matrix.contains("<c:out value=\"${matrix.getTruncatedColumnHeader(headerIndex.getIndex())}\"/>"));
	}

	@Test
	public void registrationAddAdminAndEditViewsEscapeEntityFields() throws IOException {
		String registration = new String(
				Files.readAllBytes(Paths.get("src/main/webapp/public/registration.jsp")),
				StandardCharsets.UTF_8
		);
		assertTrue(registration.contains("<option value=\"${com.id}\"><c:out value=\"${com.name}\"/></option>"));

		String addJob = read("add/job.jsp");
		assertTrue(addJob.contains("title=\"Run Job in ${fn:escapeXml(space.name)}\""));
		assertTrue(addJob.contains("<c:out value=\"${c.name}\"/>"));
		assertFalse(addJob.contains("title=\"${c.description}\""));

		String addUser = read("admin/addUser.jsp");
		assertTrue(addUser.contains("<option value=\"${com.id}\"><c:out value=\"${com.name}\"/></option>"));

		String permissions = read("admin/permissions.jsp");
		assertTrue(permissions.contains("title=\"${fn:escapeXml(t_user.fullName)}\""));

		String editBench = read("edit/benchmark.jsp");
		assertTrue(editBench.contains("title=\"Edit ${fn:escapeXml(bench.name)}\""));
		assertTrue(editBench.contains("<c:out value=\"${bench.description}\"/>"));

		String editConfig = read("edit/configuration.jsp");
		assertTrue(editConfig.contains("title=\"Edit ${fn:escapeXml(config.name)}\""));
		assertFalse(editConfig.contains("<c:out value=\"${config.description}\"/>"));
		assertFalse(editConfig.contains("<c:out value=\"${contents}\"/>"));
	}
}
