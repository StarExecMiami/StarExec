package org.starexec.test.unit.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.starexec.data.security.ValidatorStatusCode;
import org.starexec.util.XMLUtil;

import java.io.File;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code primary} attribute of a {@code PipelineStage} is typed {@code xs:boolean}, and
 * the two halves of reading it have to agree: the schema decides what a document may say, and
 * the parser decides what those words mean. They did not agree. {@code Boolean.parseBoolean}
 * answers false for everything that is not "true", so a schema-valid {@code primary="1"} was
 * read as false -- and the caller went on to mark the stage primary anyway, because it only
 * checked whether the attribute was present.
 */
public class XsdBooleanTest {

	private static final String JOBS_NS = "https://www.starexec.org/starexec/public/batchJobSchema.xsd";
	private static final String JOB_SCHEMA_REF =
			"https://starexec.example.org/starexec/public/batchJobSchema.xsd";

	@TempDir
	public File folder;

	/** All four lexical forms, and nothing else. */
	@Test
	public void theLexicalSpaceIsTrueFalseOneAndZero() {
		assertEquals(Boolean.TRUE, XMLUtil.parseXsdBoolean("true"));
		assertEquals(Boolean.TRUE, XMLUtil.parseXsdBoolean("1"));
		assertEquals(Boolean.FALSE, XMLUtil.parseXsdBoolean("false"));
		assertEquals(Boolean.FALSE, XMLUtil.parseXsdBoolean("0"));

		// The schema collapses whitespace before the lexical check.
		assertEquals(Boolean.TRUE, XMLUtil.parseXsdBoolean("  true "));
		assertEquals(Boolean.FALSE, XMLUtil.parseXsdBoolean("\t0\n"));
	}

	/**
	 * Anything outside the space is reported as unknown rather than as false, so a caller can
	 * tell a malformed value from a negative one.
	 */
	@Test
	public void anythingElseIsUnknownRatherThanFalse() {
		for (String outside : new String[]{"", " ", "TRUE", "True", "FALSE", "yes", "no", "2",
				"-1", "01", "t", "f", "null"}) {
			assertNull(XMLUtil.parseXsdBoolean(outside),
					"'" + outside + "' is not in the xs:boolean lexical space");
		}
		assertNull(XMLUtil.parseXsdBoolean(null));
	}

	/** The defect in one line: the JDK's parser disagrees with the schema on "1". */
	@Test
	public void theJdkParserDisagreesWithTheSchemaOnOne() {
		assertFalse(Boolean.parseBoolean("1"),
				"this is why parseBoolean cannot read an xs:boolean");
		assertEquals(Boolean.TRUE, XMLUtil.parseXsdBoolean("1"));
	}

	/**
	 * The other half of the contract: the schema really does admit {@code 1} and {@code 0} and
	 * really does reject the near-misses above. Without this, the parser could be "fixed" to
	 * match a lexical space the schema does not actually have.
	 */
	@Test
	public void theSchemaAdmitsExactlyThatLexicalSpace() throws Exception {
		for (String accepted : new String[]{"true", "false", "1", "0"}) {
			ValidatorStatusCode status = validatePrimary(accepted);
			assertTrue(status.isSuccess(), "primary=\"" + accepted + "\" is schema-valid but was rejected: "
					+ status.getMessage());
		}

		for (String rejected : new String[]{"TRUE", "True", "yes", "2", "-1", ""}) {
			ValidatorStatusCode status = validatePrimary(rejected);
			assertFalse(status.isSuccess(),
					"primary=\"" + rejected + "\" is not schema-valid but was accepted");
		}
	}

	/**
	 * A stage may carry the attribute without being primary. This is the shape that used to
	 * mark the stage primary regardless of its value, because presence was the only test.
	 */
	@Test
	public void anExplicitlyNonPrimaryStageIsSchemaValid() throws Exception {
		ValidatorStatusCode status = validatePrimary("false");
		assertTrue(status.isSuccess(),
				"primary=\"false\" must be a valid document: " + status.getMessage());
		assertEquals(Boolean.FALSE, XMLUtil.parseXsdBoolean("false"));
	}

	/** Omitting the attribute is valid too -- the schema documents first-stage as the default. */
	@Test
	public void omittingTheAttributeIsSchemaValid() throws Exception {
		ValidatorStatusCode status = XMLUtil.validateAgainstSchema(
				pipelineDocument("<PipelineStage config-id=\"1\"/>"), JOB_SCHEMA_REF);
		assertTrue(status.isSuccess(),
				"a stage with no primary attribute must be valid: " + status.getMessage());
	}

	private ValidatorStatusCode validatePrimary(String value) throws Exception {
		return XMLUtil.validateAgainstSchema(
				pipelineDocument("<PipelineStage config-id=\"1\" primary=\"" + value + "\"/>"),
				JOB_SCHEMA_REF);
	}

	private File pipelineDocument(String stage) throws Exception {
		File file = new File(folder, "pipeline.xml");
		Files.writeString(file.toPath(),
				"<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
						+ "<tns:Jobs xmlns:tns=\"" + JOBS_NS + "\">\n"
						+ "  <SolverPipeline name=\"p\">\n"
						+ "    " + stage + "\n"
						+ "  </SolverPipeline>\n"
						+ "  <Job name=\"j\">\n"
						+ "    <JobAttributes>\n"
						+ "      <queue-id value=\"1\"/>\n"
						+ "      <cpu-timeout value=\"2\"/>\n"
						+ "      <wallclock-timeout value=\"2\"/>\n"
						+ "      <mem-limit value=\"2.0\"/>\n"
						+ "    </JobAttributes>\n"
						+ "  </Job>\n"
						+ "</tns:Jobs>\n");
		return file;
	}
}
