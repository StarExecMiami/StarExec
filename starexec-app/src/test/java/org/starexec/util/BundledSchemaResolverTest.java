package org.starexec.util;

import org.junit.Test;
import org.w3c.dom.ls.LSInput;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The resolver, driven directly.
 *
 * <p>This is the one boundary an uploaded document cannot reach, and therefore the one the
 * loopback tests in {@code SchemaResolutionTest} cannot cover. A schema's own
 * {@code <xs:import>} is trusted input: the compiled schema handed to the validator is fixed
 * and an instance document's location hints are never consulted, so the only thing between a
 * mistaken or malicious import inside a bundled schema and an outbound fetch is this class.
 *
 * <p>In the same package as the subject because the resolver is an implementation detail that
 * should stay package-private, and driving it through {@code validateAgainstSchema} does not
 * reach it: that method rejects an unlisted reference while choosing the root schema, long
 * before any resolver runs. A test written that way passes without exercising anything --
 * which is exactly what the first version of it did.
 */
public class BundledSchemaResolverTest {

	private static final String TYPE = "http://www.w3.org/2001/XMLSchema";

	private final XMLUtil.BundledSchemaResolver resolver = new XMLUtil.BundledSchemaResolver();

	@Test
	public void aBundledReferenceResolvesToThePackagedCopy() throws Exception {
		LSInput input = resolver.resolveResource(
				TYPE, "@Web.URL@public/jobSchemaTypes.xsd", null,
				"@Web.URL@public/jobSchemaTypes.xsd", null);

		assertNotNull("a bundled import must resolve", input);
		try (InputStream stream = input.getByteStream()) {
			assertNotNull("the resolved input must carry the packaged bytes", stream);
			String schema = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
			assertTrue("the resolved document must be jobSchemaTypes.xsd, was:\n"
							+ schema.substring(0, Math.min(200, schema.length())),
					schema.contains("targetNamespace=\"@Web.URL@public/jobSchemaTypes.xsd\""));
		}
	}

	/** Every name in the allowlist must actually be present in the packaged deployment. */
	@Test
	public void everyAllowlistedNameIsPackaged() throws Exception {
		for (String name : new String[]{"batchJobSchema.xsd", "batchSpaceSchema.xsd",
				"jobSchemaTypes.xsd", "runSolverOnUploadBatchJobSchema.xsd"}) {
			LSInput input = resolver.resolveResource(TYPE, null, null, "any/path/" + name, null);
			assertNotNull(name + " is allowlisted but did not resolve", input);
			try (InputStream stream = input.getByteStream()) {
				assertNotNull(name + " is allowlisted but is not packaged", stream);
			}
		}
	}

	/**
	 * An unlisted reference must abort, not return null.
	 *
	 * <p>JAXP defines a null result as "resolve this the normal way", so returning null hands
	 * the reference straight back to the processor's default resolution -- the opposite of
	 * denying it. Throwing aborts schema construction instead.
	 */
	@Test
	public void anUnlistedReferenceAborts() {
		for (String unlisted : new String[]{
				"https://attacker.example.com/evil.xsd",
				"http://169.254.169.254/latest/meta-data/",
				"file:///etc/passwd",
				"../../../../etc/passwd",
				"evil.xsd",
				null}) {
			try {
				resolver.resolveResource(TYPE, "urn:whatever", null, unlisted, null);
				fail("'" + unlisted + "' must not be resolvable");
			} catch (XMLUtil.UnresolvableSchemaReference expected) {
				assertTrue("the diagnostic should name the refused reference",
						expected.getMessage().contains("outside the bundled set"));
			}
		}
	}

	/**
	 * And it aborts without reaching the network, even when the reference is a URL that
	 * something is listening on. A resolver that returned null here would have let the
	 * processor fetch it.
	 */
	@Test
	public void anUnlistedReferenceIsNotFetched() throws Exception {
		try (ServerSocket listener = new ServerSocket(0, 16, InetAddress.getLoopbackAddress())) {
			String reachable = "http://127.0.0.1:" + listener.getLocalPort() + "/attacker.xsd";
			try {
				resolver.resolveResource(TYPE, "urn:attacker", null, reachable, null);
				fail("a reachable unlisted reference must still be refused");
			} catch (XMLUtil.UnresolvableSchemaReference expected) {
				// intended
			}

			listener.setSoTimeout(400);
			int connections = 0;
			while (true) {
				try (Socket accepted = listener.accept()) {
					connections++;
				} catch (SocketTimeoutException none) {
					break;
				}
			}
			assertEquals("the refused reference was fetched", 0, connections);
		}
	}
}
