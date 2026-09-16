package org.starexec.test.junit.util;

import org.junit.Test;
import org.starexec.util.Util;

import java.net.URI;
import java.net.URL;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * An archive fetched by URL is saved under the last segment of the URL's path, and only a plain
 * file name is accepted.
 */
public class ArchiveNameFromUrlTests {

	@Test
	public void theNameIsTheLastPathSegment() throws Exception {
		assertEquals("cvc5.zip", Util.archiveNameFromUrl(url("https://example.org/solvers/cvc5.zip")));
	}

	@Test
	public void theQueryAndFragmentAreNotPartOfTheName() throws Exception {
		assertEquals("cvc5.zip", Util.archiveNameFromUrl(url("https://example.org/solvers/cvc5.zip?v=1#top")));
		assertEquals("cvc5.zip", Util.archiveNameFromUrl("https://example.org/cvc5.zip?next=/other/x.zip"));
	}

	@Test
	public void anEncodedSlashStaysPartOfTheName() throws Exception {
		assertEquals("a%2Fb.zip", Util.archiveNameFromUrl(url("https://example.org/a%2Fb.zip")));
	}

	@Test
	public void aUrlEndingInADirectoryNamesNoArchive() throws Exception {
		assertNull(Util.archiveNameFromUrl(url("https://example.org/solvers/")));
		assertNull(Util.archiveNameFromUrl(url("https://example.org")));
	}

	@Test
	public void relativeSegmentsNameNoArchive() throws Exception {
		assertNull(Util.archiveNameFromUrl(url("https://example.org/solvers/..")));
		assertNull(Util.archiveNameFromUrl(url("https://example.org/solvers/.")));
	}

	@Test
	public void aMissingOrUnparseableUrlNamesNoArchive() {
		assertNull(Util.archiveNameFromUrl((URL) null));
		assertNull(Util.archiveNameFromUrl((String) null));
		assertNull(Util.archiveNameFromUrl("not a url"));
	}

	private static URL url(String url) throws Exception {
		return URI.create(url).toURL();
	}
}
