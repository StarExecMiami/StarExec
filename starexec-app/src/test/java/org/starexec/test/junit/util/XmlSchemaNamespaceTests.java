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
 * <p>Every assertion here reads one side of the contract from the shipped schema files and
 * the other from the production code the exporters call, so the two halves cannot drift apart
 * again. Nothing in this class restates a namespace as a literal: a test that hard-codes both
 * sides of an equality passes whatever the product does.
 *
 * <p>{@code BatchUtil} and {@code JobToXMLer} build their root element and then recurse
 * straight into database lookups, so they cannot be called whole without a deployment. The
 * root element is the part this change touches, and it is reached here through the same
 * {@link XMLUtil} factories those two exporters call.
 */
public class XmlSchemaNamespaceTests {

	private static final List<String> SCHEMA_NAMES = Arrays.asList(
			"batchSpaceSchema.xsd",
			"batchJobSchema.xsd",
			"jobSchemaTypes.xsd",
			"runSolverOnUploadBatchJobSchema.xsd");

	private static final Path PACKAGED_SCHEMA_DIR = Path.of("src/main/resources/schemas");
	private static final Path SERVED_SCHEMA_DIR = Path.of("src/main/webapp/public");

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	/**
	 * Every schema declares, as its {@code targetNamespace}, the namespace its documents are
	 * written in -- and all four sit under the one root the application stamps from.
	 * {@code batchJobSchema.xsd} and {@code runSolverOnUploadBatchJobSchema.xsd} import
	 * {@code jobSchemaTypes} by namespace, so all four have to move together.
	 */
	@Test
	public void everyBundledSchemaDeclaresItsNamespace() throws Exception {
		for (String name : SCHEMA_NAMES) {
			assertEquals(name + " does not declare the namespace its documents are written in",
					XMLUtil.SCHEMA_NAMESPACE_ROOT + name,
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
	 * The space export stamps the namespace {@code batchSpaceSchema.xsd} declares, and points
	 * a reader at the schema beside it. Read from the shipped schema, not restated here.
	 */
	@Test
	public void theSpacesRootCarriesTheShippedSchemasNamespace() throws Exception {
		Element root = XMLUtil.createSpacesRoot(XMLUtil.generateNewDocument());

		assertEquals("the space export does not stamp the namespace its schema declares",
				targetNamespace(PACKAGED_SCHEMA_DIR.resolve("batchSpaceSchema.xsd")),
				root.getNamespaceURI());
		assertEquals("tns:Spaces", root.getTagName());
		assertEquals("a reader must be pointed at the schema beside the namespace",
				root.getNamespaceURI() + " batchSpaceSchema.xsd",
				root.getAttribute("xsi:schemaLocation"));
	}

	/** The same contract for the job export, which is where the doubled slash (U15) appeared. */
	@Test
	public void theJobsRootCarriesTheShippedSchemasNamespace() throws Exception {
		Element root = XMLUtil.createJobsRoot(XMLUtil.generateNewDocument());

		assertEquals("the job export does not stamp the namespace its schema declares",
				targetNamespace(PACKAGED_SCHEMA_DIR.resolve("batchJobSchema.xsd")),
				root.getNamespaceURI());
		assertEquals("tns:Jobs", root.getTagName());
		assertEquals("a reader must be pointed at the schema beside the namespace",
				root.getNamespaceURI() + " batchJobSchema.xsd",
				root.getAttribute("xsi:schemaLocation"));
	}

	/**
	 * The U15 regression: the job export used to concatenate a root ending in "/" with a
	 * relative location beginning with "/" and stamp {@code .../starexec//public/...} as its
	 * namespace. No namespace an exporter stamps may contain "//" after the scheme.
	 */
	@Test
	public void noExportedNamespaceContainsADoubleSlash() throws Exception {
		List<Element> roots = Arrays.asList(
				XMLUtil.createSpacesRoot(XMLUtil.generateNewDocument()),
				XMLUtil.createJobsRoot(XMLUtil.generateNewDocument()));
		for (Element root : roots) {
			for (String value : Arrays.asList(
					root.getNamespaceURI(), root.getAttribute("xsi:schemaLocation"))) {
				int schemeEnd = value.indexOf("://");
				assertTrue("expected an absolute namespace, was: " + value, schemeEnd > 0);
				assertFalse("a doubled slash leaked into: " + value,
						value.indexOf("//", schemeEnd + 3) >= 0);
			}
		}
	}

	/**
	 * The round trip that pins #122: a document rooted the way the space export roots one must
	 * validate against the schema it ships with. Before the fix the schema declared the token
	 * and the document the deployment URL, so this failed with {@code cvc-elt.1.a: Cannot find
	 * the declaration of element 'tns:Spaces'}.
	 */
	@Test
	public void aSpacesDocumentFromTheExportersRootValidates() throws Exception {
		File document = writeSpacesDocument(XMLUtil.createSpacesRoot(XMLUtil.generateNewDocument()));

		ValidatorStatusCode status = XMLUtil.validateAgainstSchema(document, packagedSpaceSchema());

		assertTrue("a Spaces document rooted by the exporter did not validate: "
				+ status.getMessage(), status.isSuccess());
	}

	/**
	 * The other half: a document in a deployment-URL namespace is still refused. Accepting one
	 * would require a normalization shim, which is deliberately out of scope.
	 */
	@Test
	public void aSpacesDocumentInADeploymentNamespaceIsRefused() throws Exception {
		Document doc = XMLUtil.generateNewDocument();
		Element root = doc.createElementNS(
				"https://starexec.example.org/starexec/public/batchSpaceSchema.xsd", "tns:Spaces");
		File document = writeSpacesDocument(root);

		ValidatorStatusCode status = XMLUtil.validateAgainstSchema(document, packagedSpaceSchema());

		assertFalse("a Spaces document in a deployment-URL namespace validated: "
				+ status.getMessage(), status.isSuccess());
		assertTrue("the failure should name the undeclared root element, was: " + status.getMessage(),
				status.getMessage().contains("Spaces"));
	}

	/**
	 * The packaged schema the server validates space XML against, as a local path.
	 *
	 * <p>{@code validateAgainstSchema} takes a schema location, which it resolves by file name
	 * against the bundled set; every caller passes a path (BatchUtil.java:248, JobUtil.java:868).
	 */
	private String packagedSpaceSchema() {
		return PACKAGED_SCHEMA_DIR.resolve("batchSpaceSchema.xsd").toAbsolutePath().toString();
	}

	/** A minimal, schema-valid Spaces body under the given root, serialized as the exporter does. */
	private File writeSpacesDocument(Element root) throws Exception {
		Document document = root.getOwnerDocument();
		document.appendChild(root);

		Element space = document.createElement("Space");
		space.setAttribute("id", "1");
		space.setAttribute("name", "round-trip");
		root.appendChild(space);

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
