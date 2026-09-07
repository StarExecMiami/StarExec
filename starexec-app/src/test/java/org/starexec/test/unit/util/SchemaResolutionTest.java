package org.starexec.test.unit.util;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.starexec.data.security.ValidatorStatusCode;
import org.starexec.util.XMLUtil;

import java.io.File;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Schema resolution must be local, offline, and driven by an allowlist.
 *
 * <p>The subject under test is the <em>packaged</em> deployment, not {@code src/main}:
 * every document here is loaded through {@link Class#getResourceAsStream}, which under
 * Surefire reads {@code target/classes/schemas} -- byte for byte what the WAR ships as
 * {@code WEB-INF/classes/schemas}. Maven filters {@code src/main/resources}, so a test
 * that read the source tree would not be testing what the server loads.
 *
 * <p>Every {@code schemaLoc} argument below names a host under {@code .example.org} or
 * {@code .example.com} (RFC 2606, guaranteed never to resolve) or an unroutable
 * link-local address. A successful validation therefore proves the schema was resolved
 * from the classpath and not fetched, and the suite needs no network in either direction.
 */
public class SchemaResolutionTest {

	/** The namespace the packaged schemas actually declare -- {@code @Web.URL@} is never substituted. */
	private static final String SHIPPED_NS_PREFIX = "@Web.URL@public/";

	private static final String SCHEMA_DIR = "/schemas/";

	private static final List<String> BUNDLED_SCHEMAS = Arrays.asList(
			"batchJobSchema.xsd",
			"batchSpaceSchema.xsd",
			"jobSchemaTypes.xsd",
			"runSolverOnUploadBatchJobSchema.xsd");

	/**
	 * A deployment-shaped reference: what {@code JobXmlType.STANDARD.schemaPath} holds at
	 * runtime, pointed at a host that cannot exist.
	 */
	private static final String DEPLOYMENT_JOB_SCHEMA_REF =
			"https://starexec.example.org/starexec/public/batchJobSchema.xsd";
	private static final String DEPLOYMENT_SPACE_SCHEMA_REF =
			"https://starexec.example.org/starexec/public/batchSpaceSchema.xsd";
	private static final String DEPLOYMENT_UPLOAD_SCHEMA_REF =
			"https://starexec.example.org/starexec/public/runSolverOnUploadBatchJobSchema.xsd";

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	// ---------------------------------------------------------------- packaging

	/**
	 * Defect #8: {@code runSolverOnUploadBatchJobSchema.xsd} existed only in
	 * {@code resources/schemas}, so {@code JobXmlType.SOLVER_UPLOAD} pointed at a URL that
	 * 404s. Both copies must exist and must not drift: the served copy is what a user reads
	 * to author a document, the packaged copy is what the server validates against, and a
	 * difference between them is a contract the user cannot see.
	 */
	@Test
	public void everySchemaIsBothPackagedAndServed() throws Exception {
		for (String name : BUNDLED_SCHEMAS) {
			try (InputStream packaged = XMLUtil.class.getResourceAsStream(SCHEMA_DIR + name)) {
				assertNotNull(name + " is missing from the packaged deployment", packaged);
			}

			Path source = Path.of("src/main/resources/schemas", name);
			Path served = Path.of("src/main/webapp/public", name);
			assertTrue(name + " is not served from webapp/public", Files.isRegularFile(served));
			assertEquals(name + " differs between the packaged and served copies",
					Files.readString(source), Files.readString(served));
		}
	}

	// ------------------------------------------------- local resolution, proven

	/**
	 * The proof that resolution is local, stated as a property of the artifact rather than
	 * as a timing observation: the packaged {@code batchJobSchema.xsd} imports
	 * {@code jobSchemaTypes.xsd} at a {@code schemaLocation} that is not a URL at all. No
	 * fetch of it can succeed. {@link #shippedExampleJobValidates()} then compiles that
	 * schema and validates against it, so the import must have been satisfied locally.
	 */
	@Test
	public void theImportLocationCannotBeFetched() throws Exception {
		String schema = readPackaged("batchJobSchema.xsd");
		Matcher m = Pattern.compile("<import[^>]*schemaLocation=\"([^\"]+)\"").matcher(schema);
		assertTrue("batchJobSchema.xsd no longer declares an import to resolve", m.find());
		String location = m.group(1);

		assertTrue("the import location changed shape; revisit this proof",
				location.startsWith(SHIPPED_NS_PREFIX));
		assertFalse("the import location is now an absolute URI, so this proof no longer holds",
				URI.create(location.replace("@", "%40")).isAbsolute());
		try {
			new URL(location);
			fail("the import location resolved as a URL: local resolution is no longer proven");
		} catch (MalformedURLException expected) {
			// Unfetchable by construction. Validation succeeding elsewhere in this class
			// is therefore evidence of classpath resolution.
		}
	}

	/**
	 * The acceptance case for defect #7. Before this change {@code batchJobSchema.xsd} could
	 * not be compiled at all -- the import above was fetched over HTTP from a URL that was
	 * never substituted -- so every job-XML upload failed, including the application's own
	 * shipped example.
	 */
	@Test
	public void shippedExampleJobValidates() throws Exception {
		ValidatorStatusCode status =
				XMLUtil.validateAgainstSchema(packagedAsFile("ExampleJob.xml"), DEPLOYMENT_JOB_SCHEMA_REF);
		assertTrue("shipped ExampleJob.xml does not validate: " + status.getMessage(), status.isSuccess());
	}

	/**
	 * {@code ExampleSpace.xml} declared {@code xmlns:tns="@Web.URL@batchSpaceSchema.xsd"}
	 * while the schema's {@code targetNamespace} carries the {@code public/} segment its own
	 * {@code xsi:schemaLocation} already had, so its root element sat in a namespace no
	 * declaration existed for.
	 */
	@Test
	public void shippedExampleSpaceValidates() throws Exception {
		ValidatorStatusCode status =
				XMLUtil.validateAgainstSchema(packagedAsFile("ExampleSpace.xml"), DEPLOYMENT_SPACE_SCHEMA_REF);
		assertTrue("shipped ExampleSpace.xml does not validate: " + status.getMessage(), status.isSuccess());
	}

	/** Defect #8, end to end: the solver-upload schema now compiles and validates a document. */
	@Test
	public void solverUploadSchemaValidatesADocument() throws Exception {
		String ns = SHIPPED_NS_PREFIX + "runSolverOnUploadBatchJobSchema.xsd";
		File xml = write("upload.xml",
				"<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
						+ "<tns:Jobs xmlns:tns=\"" + ns + "\">\n"
						+ "  <Job name=\"upload\">\n"
						+ "    <JobAttributes>\n"
						+ "      <queue-id value=\"1\"/>\n"
						+ "      <cpu-timeout value=\"2\"/>\n"
						+ "      <wallclock-timeout value=\"2\"/>\n"
						+ "      <mem-limit value=\"2.0\"/>\n"
						+ "    </JobAttributes>\n"
						+ "    <UploadedSolverJobPair bench-id=\"11\" config-name=\"default\"/>\n"
						+ "  </Job>\n"
						+ "</tns:Jobs>\n");

		ValidatorStatusCode status = XMLUtil.validateAgainstSchema(xml, DEPLOYMENT_UPLOAD_SCHEMA_REF);
		assertTrue("solver-upload schema did not validate its own document shape: " + status.getMessage(),
				status.isSuccess());
	}

	// ------------------------------------------------------------- denied input

	/**
	 * {@code schemaLoc} reaches this method from {@code JobXmlType}, but the surrounding
	 * design treats a reference as data rather than as a location, so a reference outside
	 * the bundled set is refused rather than fetched. Each of these would have been an
	 * outbound request under the previous implementation.
	 */
	@Test
	public void referencesOutsideTheBundledSetAreRefused() throws Exception {
		File exampleJob = packagedAsFile("ExampleJob.xml");
		String[] refused = {
				"https://attacker.example.com/evil.xsd",
				"http://169.254.169.254/latest/meta-data/iam/security-credentials/",
				"file:///etc/passwd",
				"jar:file:///tmp/evil.jar!/evil.xsd",
				"../../../../etc/passwd",
				"",
		};

		for (String ref : refused) {
			ValidatorStatusCode status = XMLUtil.validateAgainstSchema(exampleJob, ref);
			assertFalse("'" + ref + "' was accepted as a schema reference", status.isSuccess());
			assertTrue("rejection of '" + ref + "' should name the reference, was: " + status.getMessage(),
					status.getMessage().contains("No bundled schema"));
		}
	}

	/** A null reference is a caller bug, not a fetch. It must fail closed rather than throw. */
	@Test
	public void aNullReferenceIsRefused() throws Exception {
		ValidatorStatusCode status = XMLUtil.validateAgainstSchema(packagedAsFile("ExampleJob.xml"), null);
		assertFalse("null was accepted as a schema reference", status.isSuccess());
	}

	/**
	 * A reference selects a name from the allowlist; it never becomes a path. So a
	 * traversal-shaped reference whose final segment is bundled resolves to the
	 * <em>packaged</em> schema -- it does not read the attacker's directory -- and one whose
	 * final segment is not bundled is refused outright (covered above).
	 */
	@Test
	public void aPathLikeReferenceSelectsTheBundledCopyRatherThanTraversing() throws Exception {
		File planted = folder.newFile("batchJobSchema.xsd");
		Files.writeString(planted.toPath(), "<?xml version=\"1.0\"?><not-a-schema/>");

		ValidatorStatusCode status = XMLUtil.validateAgainstSchema(
				packagedAsFile("ExampleJob.xml"), planted.getAbsolutePath());

		assertTrue("a filesystem path was read instead of the bundled schema: " + status.getMessage(),
				status.isSuccess());
	}

	/**
	 * The plan's "no network dependency" criterion, measured rather than assumed.
	 *
	 * <p>It cannot be assumed, because the JAXP access restrictions this class sets are
	 * <em>inert</em> here: {@code xerces:xercesImpl:2.12.2} arrives transitively via
	 * {@code org.owasp.antisamy} and registers itself as the JAXP provider, and neither its
	 * {@code SchemaFactory} nor its {@code Validator} recognises {@code accessExternalDTD} or
	 * {@code accessExternalSchema}. So what stops an outbound request is the design -- a
	 * fixed, classpath-loaded schema, with the instance document's own location hints never
	 * consulted -- and that is a property of the parser, which only a measurement can pin.
	 *
	 * <p>The listener is bound to loopback on an ephemeral port, so this needs no network in
	 * either direction. Validation is synchronous and finishes before {@code accept()} runs;
	 * any connection made would already be sitting in the backlog, so a hit returns at once
	 * and only a clean run pays the timeout.
	 */
	@Test
	public void anUploadedDocumentCannotDriveAnOutboundRequest() throws Exception {
		try (ServerSocket listener = new ServerSocket(0, 16, InetAddress.getLoopbackAddress())) {
			String reachable = "http://127.0.0.1:" + listener.getLocalPort() + "/attacker.xsd";
			String jobsNs = SHIPPED_NS_PREFIX + "batchJobSchema.xsd";

			// Each of these is a location hint an uploader controls completely.
			String[][] documents = {
					{"unknown namespace",
							" xsi:schemaLocation=\"urn:attacker " + reachable + "\""},
					{"override of the bundled namespace",
							" xsi:schemaLocation=\"" + jobsNs + " " + reachable + "\""},
					{"no-namespace hint",
							" xsi:noNamespaceSchemaLocation=\"" + reachable + "\""},
			};

			for (String[] document : documents) {
				File xml = write("hint.xml",
						"<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
								+ "<tns:Jobs xmlns:tns=\"" + jobsNs + "\""
								+ " xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\""
								+ document[1] + ">\n"
								+ "  <Job name=\"hinted\">\n"
								+ "    <JobAttributes>\n"
								+ "      <queue-id value=\"1\"/>\n"
								+ "      <cpu-timeout value=\"2\"/>\n"
								+ "      <wallclock-timeout value=\"2\"/>\n"
								+ "      <mem-limit value=\"2.0\"/>\n"
								+ "    </JobAttributes>\n"
								+ "  </Job>\n"
								+ "</tns:Jobs>\n");

				XMLUtil.validateAgainstSchema(xml, DEPLOYMENT_JOB_SCHEMA_REF);
				assertEquals("a " + document[0] + " reached the network",
						0, drainConnections(listener));
			}
		}
	}

	/**
	 * An external DTD is refused earlier still, by {@code disallow-doctype-decl} on the
	 * document builder. Asserted here because the same listener proves it is refused rather
	 * than fetched-then-ignored.
	 */
	@Test
	public void anExternalDoctypeIsRefusedRatherThanFetched() throws Exception {
		try (ServerSocket listener = new ServerSocket(0, 16, InetAddress.getLoopbackAddress())) {
			String reachable = "http://127.0.0.1:" + listener.getLocalPort() + "/attacker.dtd";
			File xml = write("doctype.xml",
					"<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
							+ "<!DOCTYPE tns:Jobs SYSTEM \"" + reachable + "\">\n"
							+ "<tns:Jobs xmlns:tns=\"" + SHIPPED_NS_PREFIX + "batchJobSchema.xsd\"/>\n");

			ValidatorStatusCode status = XMLUtil.validateAgainstSchema(xml, DEPLOYMENT_JOB_SCHEMA_REF);

			assertFalse("a document carrying a DOCTYPE was accepted", status.isSuccess());
			assertTrue("expected the doctype control to reject it, got: " + status.getMessage(),
					status.getMessage().contains("disallow-doctype-decl"));
			assertEquals("the external DTD was fetched", 0, drainConnections(listener));
		}
	}

	// ----------------------------------------------------------- documented gap

	/**
	 * <strong>Known limitation, not fixed here.</strong> The packaged schemas declare
	 * {@code targetNamespace="@Web.URL@public/..."} because the build never substitutes that
	 * token, while {@code JobToXMLer} stamps exports with the live deployment URL and
	 * historical exports carry a per-instance URL. Those documents therefore sit in a
	 * namespace no packaged schema declares, and re-import fails.
	 *
	 * <p>Choosing a namespace would change document identity for every existing export, and
	 * the evidence does not identify one historical namespace to preserve, so that decision
	 * is deliberately left open. What this test pins is the part that <em>is</em> in scope:
	 * the failure is a local schema-validation error, reached with no outbound request and
	 * no dependence on the unreachable host in the reference.
	 */
	@Test
	public void exportedNamespacesFailLocallyRatherThanOverTheNetwork() throws Exception {
		String freshExportNs = "https://starexec.example.org/starexec/public/batchJobSchema.xsd";
		String historicalNs = "https://stardev.example.org/starexec_someuser/public/batchJobSchema.xsd";

		for (String ns : new String[]{freshExportNs, historicalNs}) {
			File xml = write("export.xml",
					"<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
							+ "<tns:Jobs xmlns:tns=\"" + ns + "\">\n"
							+ "  <Job name=\"exported\">\n"
							+ "    <JobAttributes>\n"
							+ "      <queue-id value=\"1\"/>\n"
							+ "      <cpu-timeout value=\"2\"/>\n"
							+ "      <wallclock-timeout value=\"2\"/>\n"
							+ "      <mem-limit value=\"2.0\"/>\n"
							+ "    </JobAttributes>\n"
							+ "  </Job>\n"
							+ "</tns:Jobs>\n");

			ValidatorStatusCode status = XMLUtil.validateAgainstSchema(xml, DEPLOYMENT_JOB_SCHEMA_REF);

			assertFalse("the namespace round-trip gap has closed; update this test and the plan",
					status.isSuccess());
			assertTrue("expected a local schema-validation failure, got: " + status.getMessage(),
					status.getMessage().contains("is not valid because"));
			assertTrue("the failure should name the undeclared root element, got: " + status.getMessage(),
					status.getMessage().contains("Jobs"));
		}
	}

	// -------------------------------------------------------------------- setup

	private String readPackaged(String name) throws Exception {
		try (InputStream in = XMLUtil.class.getResourceAsStream(SCHEMA_DIR + name)) {
			assertNotNull(name + " is missing from the packaged deployment", in);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private File packagedAsFile(String name) throws Exception {
		return write(name, readPackaged(name));
	}

	private File write(String name, String content) throws Exception {
		File file = new File(folder.getRoot(), name);
		Files.writeString(file.toPath(), content);
		return file;
	}

	/** How many connections the listener received; zero costs one timeout, a hit costs nothing. */
	private int drainConnections(ServerSocket listener) throws Exception {
		listener.setSoTimeout(400);
		int connections = 0;
		while (true) {
			try (Socket accepted = listener.accept()) {
				connections++;
			} catch (SocketTimeoutException none) {
				return connections;
			}
		}
	}
}
