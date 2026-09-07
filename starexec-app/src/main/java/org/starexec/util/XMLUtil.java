package org.starexec.util;

import org.starexec.constants.R;
import org.starexec.data.security.ValidatorStatusCode;
import org.starexec.logger.StarLogger;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

import org.w3c.dom.ls.LSInput;
import org.w3c.dom.ls.LSResourceResolver;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Contains functionality shared between JobUtil, BatchUtil, and JobToXMLer
 *
 * @author Eric
 */
public class XMLUtil {
	private static final StarLogger log = StarLogger.getLogger(XMLUtil.class);

	/**
	 * Where the bundled schemas live on the classpath.
	 *
	 * <p>{@code src/main/resources/schemas} is packaged into {@code WEB-INF/classes/schemas},
	 * which is the one location carrying every schema this application validates against.
	 * {@code webapp/public} is a second, hand-maintained copy served over HTTP for authors to
	 * read, and it had drifted: {@code runSolverOnUploadBatchJobSchema.xsd} was missing from
	 * it entirely, so the URL {@code JobXmlType.SOLVER_UPLOAD} named returned 404.
	 */
	private static final String SCHEMA_CLASSPATH_DIR = "/schemas/";

	/**
	 * The only schema documents this application will resolve, by file name.
	 *
	 * <p>An allowlist rather than a path derived from the reference. Both references this
	 * class sees are server-side today -- {@code JobXmlType} builds one from the deployment
	 * URL, and the other is the {@code schemaLocation} written into a bundled schema -- but
	 * the first is assembled from operator-set environment variables, and neither should be
	 * able to name a document outside this set. A reference selects a member of it and is
	 * never used to build a classpath or filesystem path.
	 */
	private static final Set<String> BUNDLED_SCHEMAS;

	static {
		Set<String> schemas = new HashSet<>();
		schemas.add("batchJobSchema.xsd");
		schemas.add("batchSpaceSchema.xsd");
		schemas.add("jobSchemaTypes.xsd");
		schemas.add("runSolverOnUploadBatchJobSchema.xsd");
		BUNDLED_SCHEMAS = Collections.unmodifiableSet(schemas);
	}

	/** Which {@code "target property"} pairs have already been reported as unsupported. */
	private static final Set<String> UNSUPPORTED_PROPERTIES_REPORTED = ConcurrentHashMap.newKeySet();

	/**
	 * Resolves schema imports from the bundled copies and refuses everything else.
	 *
	 * <p>The shipped schemas declare their imports with an absolute {@code schemaLocation}
	 * built from a deployment URL, which made validation fetch a schema over HTTP from the
	 * running server -- and fail outright when that URL was never substituted. Resolving by
	 * file name against the packaged copies removes both the network dependency and the
	 * substitution requirement, and does not change any document's namespace.
	 *
	 * <p>Returning null hands the reference back to the processor's own resolution, so denial
	 * does not rest on the null alone. What bounds resolution is that no attacker-controlled
	 * reference reaches a resolver at all: the root schema is selected by name from
	 * {@link #BUNDLED_SCHEMAS} and read from the classpath, its only import is another bundled
	 * schema, and the compiled schema handed to the validator is fixed -- an uploaded
	 * document's {@code xsi:schemaLocation} and {@code xsi:noNamespaceSchemaLocation} are
	 * never consulted. That last part is a property of the bundled parser rather than
	 * something this class can assert, so {@code SchemaResolutionTest} measures it against a
	 * loopback listener instead of assuming it.
	 */
	private static final class BundledSchemaResolver implements LSResourceResolver {
		@Override
		public LSInput resolveResource(
				String type, String namespaceURI, String publicId, String systemId, String baseURI
		) {
			String name = bundledNameFor(systemId);
			if (name == null) {
				log.warn("Refusing to resolve schema resource outside the bundled set: systemId=" +
				         systemId + " namespace=" + namespaceURI);
				return null;
			}
			return new BundledSchemaInput(name, publicId, systemId, baseURI);
		}
	}

	/** An {@link LSInput} backed by a bundled classpath schema. */
	private static final class BundledSchemaInput implements LSInput {
		private final String name;
		private String publicId;
		private String systemId;
		private String baseURI;
		private String encoding = "UTF-8";
		private boolean certifiedText;

		private BundledSchemaInput(String name, String publicId, String systemId, String baseURI) {
			this.name = name;
			this.publicId = publicId;
			this.systemId = systemId;
			this.baseURI = baseURI;
		}

		@Override
		public InputStream getByteStream() {
			return XMLUtil.class.getResourceAsStream(SCHEMA_CLASSPATH_DIR + name);
		}

		@Override public Reader getCharacterStream() { return null; }
		@Override public void setCharacterStream(Reader characterStream) { }
		@Override public void setByteStream(InputStream byteStream) { }
		@Override public String getStringData() { return null; }
		@Override public void setStringData(String stringData) { }
		@Override public String getSystemId() { return systemId; }
		@Override public void setSystemId(String systemId) { this.systemId = systemId; }
		@Override public String getPublicId() { return publicId; }
		@Override public void setPublicId(String publicId) { this.publicId = publicId; }
		@Override public String getBaseURI() { return baseURI; }
		@Override public void setBaseURI(String baseURI) { this.baseURI = baseURI; }
		@Override public String getEncoding() { return encoding; }
		@Override public void setEncoding(String encoding) { this.encoding = encoding; }
		@Override public boolean getCertifiedText() { return certifiedText; }
		@Override public void setCertifiedText(boolean certifiedText) { this.certifiedText = certifiedText; }
	}

	/**
	 * Denies every protocol for one external-access property, on whichever JAXP object
	 * accepts it: {@code SchemaFactory} and {@code Validator} are configured independently,
	 * and restricting one leaves the other open.
	 *
	 * <p>The empty string is JAXP's "no protocol is permitted". This is defence in depth, not
	 * the control: {@code xerces:xercesImpl} arrives transitively through
	 * {@code org.owasp.antisamy} and registers itself as the JAXP provider, and its 2.12.2
	 * {@code SchemaFactory} and {@code Validator} recognise <em>neither</em> property -- so on
	 * this deployment all four calls are inert. Keeping them costs nothing and they take
	 * effect if the provider ever changes; the actual bound on resolution is the bundled-only
	 * schema selection described on {@link BundledSchemaResolver}.
	 *
	 * <p>An unsupported property is reported once per JVM rather than once per validation.
	 * Every job-XML upload takes this path, and the stack trace is the same each time: at
	 * WARN with a trace it drowned the log and drove a failing {@code ErrorLogs} DB write per
	 * upload.
	 */
	private static void setExternalAccessRestriction(String target, String property, PropertySetter setter) {
		try {
			setter.set(property, "");
		} catch (SAXException e) {
			if (UNSUPPORTED_PROPERTIES_REPORTED.add(target + ' ' + property)) {
				log.warn(target + " does not support " + property +
				         "; schema resolution stays bounded by the bundled-schema allowlist");
			}
		}
	}

	/** {@code SchemaFactory} and {@code Validator} share this signature but no supertype. */
	@FunctionalInterface
	private interface PropertySetter {
		void set(String name, Object value) throws SAXException;
	}

	/**
	 * The bundled schema a reference names, or null if it names anything else.
	 *
	 * <p>Only the final path segment is considered, and only as a lookup key into
	 * {@link #BUNDLED_SCHEMAS}. Nothing from the reference reaches a file or classpath path.
	 */
	private static String bundledNameFor(String reference) {
		if (reference == null) {
			return null;
		}
		String trimmed = reference.trim();
		int lastSlash = trimmed.lastIndexOf('/');
		if (lastSlash >= 0) {
			trimmed = trimmed.substring(lastSlash + 1);
		}
		return BUNDLED_SCHEMAS.contains(trimmed) ? trimmed : null;
	}

	/**
	 * Validates an XML document using a schema
	 *
	 * @param file The XML file
	 * @param schemaLoc The absolute path to the schema
	 * @return A ValidatorStatusCode containing true if the validation was successful and false plus an
	 * error message otherwise
	 * @throws ParserConfigurationException
	 * @throws IOException
	 */
	public static ValidatorStatusCode validateAgainstSchema(File file, String schemaLoc) throws
			ParserConfigurationException, IOException {
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setValidating(false);//This is true for DTD, but not W3C XML Schema that we're using
		factory.setNamespaceAware(true);
		
		// SECURITY: Disable XXE (XML External Entity) attacks
		try {
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
			factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
			factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
			factory.setXIncludeAware(false);
			factory.setExpandEntityReferences(false);
		} catch (ParserConfigurationException e) {
			log.error("Failed to configure XXE protection", e);
			throw e;
		}

		// The schema is selected by name from the bundled set, never fetched from schemaLoc.
		// schemaLoc has always been a deployment URL or an absolute path, so honouring it
		// made validation depend on the network -- and on a build-time substitution that no
		// longer happens, which is why every job XML upload failed. Resolving locally fixes
		// that without altering any document's namespace.
		String rootSchema = bundledNameFor(schemaLoc);
		if (rootSchema == null) {
			final String message =
					"No bundled schema is available for '" + schemaLoc + "'";
			log.error("validateAgainstSchema - " + message);
			return new ValidatorStatusCode(false, message);
		}

		SchemaFactory schemaFactory = SchemaFactory.newInstance("http://www.w3.org/2001/XMLSchema");

		// Deny arbitrary external schema/DTD resolution. Combined with the resolver below,
		// an uploaded document cannot drive a fetch of anything outside the bundled set.
		setExternalAccessRestriction("SchemaFactory", XMLConstants.ACCESS_EXTERNAL_DTD, schemaFactory::setProperty);
		setExternalAccessRestriction("SchemaFactory", XMLConstants.ACCESS_EXTERNAL_SCHEMA, schemaFactory::setProperty);
		schemaFactory.setResourceResolver(new BundledSchemaResolver());

		try (InputStream rootStream =
				     XMLUtil.class.getResourceAsStream(SCHEMA_CLASSPATH_DIR + rootSchema)) {
			if (rootStream == null) {
				final String message = "Bundled schema '" + rootSchema + "' is missing from the deployment";
				log.error("validateAgainstSchema - " + message);
				return new ValidatorStatusCode(false, message);
			}

			StreamSource rootSource = new StreamSource(rootStream);
			// A stable systemId so relative imports have a base to resolve against; the
			// resolver keys on the file name, so this never becomes a fetchable location.
			rootSource.setSystemId(SCHEMA_CLASSPATH_DIR + rootSchema);

			factory.setSchema(schemaFactory.newSchema(new Source[]{rootSource}));
			Schema schema = factory.getSchema();
			DocumentBuilder builder = factory.newDocumentBuilder();
			Document document = builder.parse(file);
			Validator validator = schema.newValidator();
			// The same restrictions again: newSchema() and validate() are separately
			// configurable, so restricting one would leave the other open. Both are inert
			// under the bundled Xerces -- see setExternalAccessRestriction.
			setExternalAccessRestriction("Validator", XMLConstants.ACCESS_EXTERNAL_DTD, validator::setProperty);
			setExternalAccessRestriction("Validator", XMLConstants.ACCESS_EXTERNAL_SCHEMA, validator::setProperty);
			validator.setResourceResolver(new BundledSchemaResolver());
			DOMSource source = new DOMSource(document);
			validator.validate(source);
			log.debug("XML File has been validated against the schema.");
			return new ValidatorStatusCode(true);
		} catch (SAXException ex) {
			final String message = "File '" + file.getName() + "' is not valid because: \"" + ex.getMessage() + "\"";
			log.warn(message);
			return new ValidatorStatusCode(false, message);
		}

	}

	/**
	 * Generates a new, empty XML Document object
	 *
	 * @return The new Document
	 * @throws ParserConfigurationException
	 */
	public static Document generateNewDocument() throws ParserConfigurationException {
		DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
		
		// SECURITY: Disable XXE (XML External Entity) attacks
		try {
			docFactory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			docFactory.setFeature("http://xml.org/sax/features/external-general-entities", false);
			docFactory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
			docFactory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
			docFactory.setXIncludeAware(false);
			docFactory.setExpandEntityReferences(false);
		} catch (ParserConfigurationException e) {
			log.error("Failed to configure XXE protection", e);
			throw e;
		}

		DocumentBuilder docBuilder = docFactory.newDocumentBuilder();

		return docBuilder.newDocument();
	}

	/**
	 * Writes XML Document object out to a file and returns that file
	 *
	 * @param relPath The path to where the file should be place, relative to R.STAREXEC_ROOT.
	 * Any spaces will be removed from the name
	 * @param doc
	 * @return The File where the XML document was saved
	 * @throws Exception
	 */
	public static File writeDocumentToFile(String relPath, Document doc) throws Exception {
		//no spaces are permitted at the top level
		relPath = relPath.replaceAll("\\s+", "");
		// write the content into xml file
		TransformerFactory transformerFactory = TransformerFactory.newInstance();
		Transformer transformer = transformerFactory.newTransformer();
		transformer.setOutputProperty(OutputKeys.INDENT, "yes");
		DOMSource source = new DOMSource(doc);

		//we can't let the top level have spaces in the name
		File file = new File(R.STAREXEC_ROOT, relPath);
		log.debug(file.getAbsolutePath());
		StreamResult result = new StreamResult(file);
		transformer.transform(source, result);
		return file;
	}
}
