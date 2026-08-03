package org.starexec.app;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.apache.taglibs.standard.functions.Functions;
import org.junit.Test;
import org.starexec.data.to.Space;
import org.starexec.util.DataTablesQuery;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
}
