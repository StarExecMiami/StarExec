package org.starexec.test.unit.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.starexec.data.security.ValidatorStatusCode;
import org.starexec.util.XMLUtil;
import org.xml.sax.SAXNotRecognizedException;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;

import java.io.File;
import java.io.InputStream;
import java.io.StringReader;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.starexec.test.TemporaryFolders.newFile;

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

	/** The namespace the packaged schemas declare; fixed, so it does not depend on the deployment. */
	private static final String SHIPPED_NS_PREFIX = "https://www.starexec.org/starexec/public/";

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

	@TempDir
	public File folder;

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
				assertNotNull(packaged, name + " is missing from the packaged deployment");
			}

			Path source = Path.of("src/main/resources/schemas", name);
			Path served = Path.of("src/main/webapp/public", name);
			assertTrue(Files.isRegularFile(served), name + " is not served from webapp/public");
			assertEquals(Files.readString(source),
					Files.readString(served), name + " differs between the packaged and served copies");
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
		assertTrue(m.find(), "batchJobSchema.xsd no longer declares an import to resolve");
		String location = m.group(1);

		assertTrue(BUNDLED_SCHEMAS.contains(location),
				"the import location changed shape; revisit this proof");
		assertFalse(URI.create(location.replace("@", "%40")).isAbsolute(),
				"the import location is now an absolute URI, so this proof no longer holds");
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
		assertTrue(status.isSuccess(), "shipped ExampleJob.xml does not validate: " + status.getMessage());
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
		assertTrue(status.isSuccess(), "shipped ExampleSpace.xml does not validate: " + status.getMessage());
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
		assertTrue(status.isSuccess(),
				"solver-upload schema did not validate its own document shape: " + status.getMessage());
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
			assertFalse(status.isSuccess(), "'" + ref + "' was accepted as a schema reference");
			assertTrue(status.getMessage().contains("No bundled schema"),
					"rejection of '" + ref + "' should name the reference, was: " + status.getMessage());
		}
	}

	/** A null reference is a caller bug, not a fetch. It must fail closed rather than throw. */
	@Test
	public void aNullReferenceIsRefused() throws Exception {
		ValidatorStatusCode status = XMLUtil.validateAgainstSchema(packagedAsFile("ExampleJob.xml"), null);
		assertFalse(status.isSuccess(), "null was accepted as a schema reference");
	}

	/**
	 * A reference selects a name from the allowlist; it never becomes a path. So a
	 * traversal-shaped reference whose final segment is bundled resolves to the
	 * <em>packaged</em> schema -- it does not read the attacker's directory -- and one whose
	 * final segment is not bundled is refused outright (covered above).
	 */
	@Test
	public void aPathLikeReferenceSelectsTheBundledCopyRatherThanTraversing() throws Exception {
		File planted = newFile(folder, "batchJobSchema.xsd");
		Files.writeString(planted.toPath(), "<?xml version=\"1.0\"?><not-a-schema/>");

		ValidatorStatusCode status = XMLUtil.validateAgainstSchema(
				packagedAsFile("ExampleJob.xml"), planted.getAbsolutePath());

		assertTrue(status.isSuccess(),
				"a filesystem path was read instead of the bundled schema: " + status.getMessage());
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
				assertEquals(0,
						drainConnections(listener), "a " + document[0] + " reached the network");
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

			assertFalse(status.isSuccess(), "a document carrying a DOCTYPE was accepted");
			assertTrue(status.getMessage().contains("disallow-doctype-decl"),
					"expected the doctype control to reject it, got: " + status.getMessage());
			assertEquals(0, drainConnections(listener), "the external DTD was fetched");
		}
	}

	/**
	 * The provider must accept the external-access restrictions, and the one the classpath
	 * would otherwise select does not.
	 *
	 * <p>Service-provider lookup finds {@code xerces:xercesImpl:2.12.2}, which arrives
	 * transitively through {@code org.owasp.antisamy} and rejects both properties on both
	 * objects -- so under {@code SchemaFactory.newInstance} all four restrictions were inert
	 * and the code merely logged that. This pins both halves: the classpath provider is still
	 * the broken one, and the provider actually used accepts the restrictions.
	 */
	@Test
	public void theProviderInUseAcceptsTheExternalAccessRestrictions() throws Exception {
		SchemaFactory defaultFactory = SchemaFactory.newDefaultInstance();
		defaultFactory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
		defaultFactory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

		Validator validator = defaultFactory
				.newSchema(new StreamSource(new StringReader(MINIMAL_SCHEMA)))
				.newValidator();
		validator.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
		validator.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

		// And the reason the code no longer uses service-provider lookup.
		SchemaFactory lookedUp = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
		try {
			lookedUp.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
			// If this ever starts working the comment in XMLUtil is stale, but nothing is
			// unsafe: the code uses the default provider either way.
		} catch (SAXNotRecognizedException expected) {
			assertTrue(lookedUp.getClass().getName().contains("xerces"),
					"the classpath provider should be the one that cannot be restricted");
		}
	}

	/** Every import the bundled schemas actually declare still resolves. */
	@Test
	public void everyBundledImportResolves() throws Exception {
		int imports = 0;
		for (String name : BUNDLED_SCHEMAS) {
			Matcher m = Pattern.compile("<import[^>]*schemaLocation=\"([^\"]+)\"")
					.matcher(readPackaged(name));
			while (m.find()) {
				imports++;
				assertTrue(BUNDLED_SCHEMAS.contains(m.group(1).substring(m.group(1).lastIndexOf('/') + 1)),
						name + " imports '" + m.group(1) + "', which is not bundled");
			}
		}
		assertTrue(imports > 0, "the bundled schemas should declare imports to resolve");
	}

	// ------------------------------------------------ documents from other instances

	/**
	 * A document written in another instance's namespace is refused, locally.
	 *
	 * <p>The packaged schemas and the producers now agree on one fixed namespace, so a
	 * document this instance exports re-imports here and on any other instance. Documents
	 * from before that — a deployment URL, or a per-instance stardev URL — sit in a
	 * namespace no packaged schema declares, and they are still refused. Accepting them
	 * would need a normalization step before validation, which is deliberately not done:
	 * the schema is the contract.
	 *
	 * <p>What this test pins is that the refusal is a local schema-validation error, reached
	 * with no outbound request and no dependence on the unreachable host in the reference.
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

			assertFalse(status.isSuccess(),
					"the namespace round-trip gap has closed; update this test and the plan");
			assertTrue(status.getMessage().contains("is not valid because"),
					"expected a local schema-validation failure, got: " + status.getMessage());
			assertTrue(status.getMessage().contains("Jobs"),
					"the failure should name the undeclared root element, got: " + status.getMessage());
		}
	}

	// -------------------------------------------------------------------- setup

	private static final String MINIMAL_SCHEMA =
			"<?xml version=\"1.0\"?><schema xmlns=\"http://www.w3.org/2001/XMLSchema\""
			+ " targetNamespace=\"urn:t\"><element name=\"r\" type=\"string\"/></schema>";

	private String readPackaged(String name) throws Exception {
		try (InputStream in = XMLUtil.class.getResourceAsStream(SCHEMA_DIR + name)) {
			assertNotNull(in, name + " is missing from the packaged deployment");
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private File packagedAsFile(String name) throws Exception {
		return write(name, readPackaged(name));
	}

	private File write(String name, String content) throws Exception {
		File file = new File(folder, name);
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
