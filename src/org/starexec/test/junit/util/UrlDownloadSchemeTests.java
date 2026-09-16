package org.starexec.test.junit.util;

import org.junit.Test;
import org.starexec.util.Util;

import java.io.File;
import java.net.URL;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * An archive a user names by URL is fetched over http or https only.
 *
 * <p>{@code Util.copyFileFromURLUsingProxy} opened whatever URL it was given, so a scheme other
 * than http or https was handled by the JVM's own handler for that scheme rather than by a
 * download. The validators refuse such a URL now, and the download refuses it again.
 *
 * <p>These are plain assertions on the scheme check; nothing is downloaded. Note that the file
 * named by the {@code file:} cases below need not exist: the scheme is rejected before the URL
 * is opened, which is the property under test.
 */
public class UrlDownloadSchemeTests {

	@Test
	public void httpAndHttpsUrlsAreDownloadable() throws Exception {
		assertTrue(Util.isDownloadableUrl(new URL("http://example.org/solver.zip")));
		assertTrue(Util.isDownloadableUrl(new URL("https://example.org/solver.zip")));
		assertTrue("the scheme is not case sensitive",
				Util.isDownloadableUrl("HTTPS://example.org/solver.zip"));
	}

	@Test
	public void otherSchemesAreNotDownloadable() throws Exception {
		assertFalse(Util.isDownloadableUrl(new URL("file:///srv/starexec/solver.zip")));
		assertFalse(Util.isDownloadableUrl(new URL("jar:file:///srv/starexec/solvers.zip!/solver.zip")));
		assertFalse(Util.isDownloadableUrl(new URL("ftp://example.org/solver.zip")));
	}

	@Test
	public void aMissingOrUnparseableUrlIsNotDownloadable() {
		assertFalse(Util.isDownloadableUrl((URL) null));
		assertFalse(Util.isDownloadableUrl((String) null));
		assertFalse(Util.isDownloadableUrl("not a url"));
	}

	/** The download refuses the scheme itself, so a caller that skips the validator is covered. */
	@Test
	public void aRefusedSchemeIsNotCopied() throws Exception {
		File destination = File.createTempFile("url-download-", ".zip");
		assertTrue(destination.delete());

		assertFalse(Util.copyFileFromURLUsingProxy(new URL("file:///srv/starexec/solver.zip"), destination));
		assertFalse("nothing is written", destination.exists());
	}
}
