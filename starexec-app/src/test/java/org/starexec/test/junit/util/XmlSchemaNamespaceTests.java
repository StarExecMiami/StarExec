package org.starexec.test.junit.util;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.starexec.data.security.ValidatorStatusCode;
import org.starexec.util.XMLUtil;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The namespace contract of the exported space and job XML, pinned in one place (#122).
 *
 * <p>A namespace is an identifier, not a location: nothing fetches it, so it must not depend
 * on where an instance happens to be deployed. The schemas nevertheless declared an Ant-era
 * {@code @Web.URL@public/...} token that no Maven property substitutes, while the producers
 * stamped the live deployment URL, so an export could never validate against the schema it
 * shipped with -- and no two instances could exchange documents.
 *
 * <p>The contract is now a fixed {@code https://www.starexec.org/starexec/public/...}
 * namespace, declared by the four bundled schemas and stamped by the producers. This test
 * reads the shipped schema files, not copies, so the two halves cannot drift apart again,
 * and it exercises the real validator rather than a hand-rolled parser.
 */
public class XmlSchemaNamespaceTests {

	// The four namespaces the schemas and the producers must agree on. These are the
	// canonical values this change introduces; the implementation is expected to expose
	// them once and use them from both sides.
	private static final String SPACE_SCHEMA_NAMESPACE =
			"https://www.starexec.org/starexec/public/batchSpaceSchema.xsd";
	private static final String JOB_SCHEMA_NAMESPACE =
			"https://www.starexec.org/starexec/public/batchJobSchema.xsd";
	private static final String JOB_SCHEMA_TYPES_NAMESPACE =
			"https://www.starexec.org/starexec/public/jobSchemaTypes.xsd";
	private static final String SOLVER_UPLOAD_SCHEMA_NAMESPACE =
			"https://www.starexec.org/starexec/public/runSolverOnUploadBatchJobSchema.xsd";

	private static final List<String> SCHEMA_NAMES = Arrays.asList(
			"batchSpaceSchema.xsd",
			"batchJobSchema.xsd",
			"jobSchemaTypes.xsd",
			"runSolverOnUploadBatchJobSchema.xsd");
	private static final List<String> SCHEMA_NAMESPACES = Arrays.asList(
			SPACE_SCHEMA_NAMESPACE,
			JOB_SCHEMA_NAMESPACE,
			JOB_SCHEMA_TYPES_NAMESPACE,
			SOLVER_UPLOAD_SCHEMA_NAMESPACE);

	private static final Path PACKAGED_SCHEMA_DIR = Path.of("src/main/resources/schemas");
	private static final Path SERVED_SCHEMA_DIR = Path.of("src/main/webapp/public");

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	/**
	 * Every schema declares, as its {@code targetNamespace}, the namespace its documents are
	 * written in. {@code batchJobSchema.xsd} and {@code runSolverOnUploadBatchJobSchema.xsd}
	 * import {@code jobSchemaTypes} by namespace, so all four have to move together.
	 */
	@Test
	public void everyBundledSchemaDeclaresItsNamespace() throws Exception {
		for (int index = 0; index < SCHEMA_NAMES.size(); index++) {
			String name = SCHEMA_NAMES.get(index);
			assertEquals(name + " does not declare the namespace its documents are written in",
					SCHEMA_NAMESPACES.get(index),
					targetNamespace(PACKAGED_SCHEMA_DIR.resolve(name)));
		}
	}

	/** The Ant-era token must be gone from the schemas and from the examples served with them. */
	@Test
	public void noShippedFileStillCarriesTheFilterToken() throws Exception {
		for (Path root : Arrays.asList(PACKAGED_SCHEMA_DIR, SERVED_SCHEMA_DIR)) {
			for (Path file : regularFilesUnder(root)) {
				// ISO-8859-1 maps every byte, so the binary documents in webapp/public cannot
				// make this scan fail on decoding rather than on the token.
				String content = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
				assertFalse(file + " still contains the unsubstituted @Web.URL@ token",
						content.contains("@Web.URL@"));
			}
		}
	}

	/** The packaged copy is what the server validates against; the served copy is the contract. */
	@Test
	public void thePackagedAndServedCopiesAgreeOnTheNamespace() throws Exception {
		for (String name : SCHEMA_NAMES) {
			Path packaged = PACKAGED_SCHEMA_DIR.resolve(name);
			Path served = SERVED_SCHEMA_DIR.resolve(name);
			if (Files.isRegularFile(packaged) && Files.isRegularFile(served)) {
				assertEquals(name + " declares a different namespace in each of its two copies",
						targetNamespace(packaged), targetNamespace(served));
			}
		}
	}

	/**
	 * The U15 regression: the job export used to concatenate a root ending in "/" with a
	 * relative location beginning with "/" and stamp {@code .../starexec//public/...} as its
	 * namespace. No namespace a producer can stamp may contain "//" after the scheme.
	 */
	@Test
	public void noProducedNamespaceContainsADoubleSlash() {
		for (String namespace : SCHEMA_NAMESPACES) {
			int schemeEnd = namespace.indexOf("://");
			assertTrue("expected an absolute namespace, was: " + namespace, schemeEnd > 0);
			assertFalse("a doubled slash leaked into the namespace: " + namespace,
					namespace.indexOf("//", schemeEnd + 3) >= 0);
		}
	}

	/**
	 * The round trip that pins #122: a document built the way {@code BatchUtil} builds one --
	 * root element created with the production namespace -- must validate against the schema
	 * it names. Before the fix the schema declared the token and the document the canonical
	 * namespace, so this failed with {@code cvc-elt.1.a: Cannot find the declaration of
	 * element 'tns:Spaces'}.
	 */
	@Test
	public void aSpacesDocumentInTheCanonicalNamespaceValidates() throws Exception {
		File document = writeSpacesDocument(SPACE_SCHEMA_NAMESPACE);
		// validateAgainstSchema takes a schema LOCATION, which every caller gives as a local
		// path (BatchUtil.java:251, JobUtil.java:868). Passing the namespace here would make
		// the parser fetch it over the network, which no test may do.
		ValidatorStatusCode status = XMLUtil.validateAgainstSchema(document, packagedSpaceSchema());
		assertTrue("a Spaces document in the canonical namespace did not validate: "
				+ status.getMessage(), status.isSuccess());
	}

	/**
	 * The other half: a document in a deployment-URL namespace is still refused. Accepting
	 * one would require a normalization shim, which is deliberately out of scope.
	 */
	@Test
	public void aSpacesDocumentInADeploymentNamespaceIsRefused() throws Exception {
		String deploymentNamespace =
				"https://starexec.example.org/starexec/public/batchSpaceSchema.xsd";
		File document = writeSpacesDocument(deploymentNamespace);
		ValidatorStatusCode status = XMLUtil.validateAgainstSchema(document, packagedSpaceSchema());
		assertFalse("a Spaces document in a deployment-URL namespace validated: "
				+ status.getMessage(), status.isSuccess());
		assertTrue("the failure should name the undeclared root element, was: " + status.getMessage(),
				status.getMessage().contains("Spaces"));
	}

	/** The packaged schema the server validates space XML against, as a local path. */
	private String packagedSpaceSchema() {
		return PACKAGED_SCHEMA_DIR.resolve("batchSpaceSchema.xsd").toAbsolutePath().toString();
	}

	/** A minimal, schema-valid Spaces body, serialized exactly as the exporter would. */
	private File writeSpacesDocument(String namespace) throws Exception {
		Document document = XMLUtil.generateNewDocument();
		Element spaces = document.createElementNS(namespace, "tns:Spaces");
		document.appendChild(spaces);

		Element space = document.createElement("Space");
		space.setAttribute("id", "1");
		space.setAttribute("name", "round-trip");
		spaces.appendChild(space);

		File file = folder.newFile("spaces.xml");
		Transformer transformer = TransformerFactory.newInstance().newTransformer();
		transformer.transform(new DOMSource(document), new StreamResult(file));
		return file;
	}

	private String targetNamespace(Path schema) throws Exception {
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setNamespaceAware(true);
		factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
		Document document = factory.newDocumentBuilder().parse(schema.toFile());
		return document.getDocumentElement().getAttribute("targetNamespace");
	}

	private List<Path> regularFilesUnder(Path root) throws Exception {
		try (Stream<Path> files = Files.walk(root)) {
			return files.filter(Files::isRegularFile).collect(Collectors.toList());
		}
	}
}
