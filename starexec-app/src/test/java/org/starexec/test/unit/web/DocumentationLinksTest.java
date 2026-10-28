package org.starexec.test.unit.web;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * Documentation links in the web pages and help files point at targets that still exist (#103).
 *
 * <p>The User Guide lived on the University of Iowa wiki, which has been retired; the same guide
 * ships with the application as {@code public/StarExecUserGuide.pdf}. NSF award pages moved from
 * FastLane to nsf.gov.
 *
 * <p>Known exceptions, not asserted here until a replacement is chosen: the development wiki home
 * page ({@code wiki.uiowa.edu/display/stardev/Home}), the virtual machine image
 * ({@code www.starexec.org/vmimage/}) and the video on the job details help page.
 */
public class DocumentationLinksTest {

	private static final Path WEBAPP = Path.of("src/main/webapp");
	private static final Path JAVA = Path.of("src/main/java");

	private static final List<String> RETIRED_HOSTS =
			List.of("wiki.uiowa.edu/display/stardev/User", "fastlane.nsf.gov");

	private static final Set<String> TEXT_EXTENSIONS =
			Set.of(".jsp", ".jspf", ".tag", ".help", ".html", ".js", ".txt", ".xml", ".java");

	/**
	 * Page count of public/StarExecUserGuide.pdf, blob 069fd6b843d20842639da383a66316b8caab3b03.
	 * PDFBox is not a dependency, so the count is fixed here; update it if the guide changes.
	 */
	private static final int USER_GUIDE_PAGES = 15;

	private static final Pattern GUIDE_PAGE_LINK = Pattern.compile("StarExecUserGuide\\.pdf#page=(\\d+)");

	@Test
	public void noLinkPointsAtARetiredHost() throws IOException {
		List<String> found = new ArrayList<>();
		for (Path file : textFiles()) {
			List<String> lines = Files.readAllLines(file, StandardCharsets.ISO_8859_1);
			for (int i = 0; i < lines.size(); i++) {
				for (String host : RETIRED_HOSTS) {
					if (lines.get(i).contains(host)) {
						found.add(file + ":" + (i + 1) + " " + host);
					}
				}
			}
		}
		assertEquals("links to retired hosts", List.of(), found);
	}

	@Test
	public void userGuidePageLinksAreWithinTheGuide() throws IOException {
		List<String> links = new ArrayList<>();
		List<String> outOfRange = new ArrayList<>();
		for (Path file : textFiles()) {
			Matcher m = GUIDE_PAGE_LINK.matcher(Files.readString(file, StandardCharsets.ISO_8859_1));
			while (m.find()) {
				int page = Integer.parseInt(m.group(1));
				links.add(file + " page " + page);
				if (page < 1 || page > USER_GUIDE_PAGES) {
					outOfRange.add(file + " page " + page);
				}
			}
		}
		assertFalse("the help files link sections of the bundled User Guide", links.isEmpty());
		assertEquals("pages beyond the guide's " + USER_GUIDE_PAGES, List.of(), outOfRange);
	}

	private static List<Path> textFiles() throws IOException {
		List<Path> files = new ArrayList<>();
		for (Path root : List.of(WEBAPP, JAVA)) {
			try (Stream<Path> walk = Files.walk(root)) {
				walk.filter(Files::isRegularFile)
						.filter(p -> !p.startsWith(WEBAPP.resolve("js/lib")))
						.filter(DocumentationLinksTest::isText)
						.forEach(files::add);
			}
		}
		return files;
	}

	private static boolean isText(Path file) {
		String name = file.getFileName().toString();
		int dot = name.lastIndexOf('.');
		return dot >= 0 && TEXT_EXTENSIONS.contains(name.substring(dot));
	}
}
