package org.starexec.test.junit.app;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Static scan of the webapp's JSP, tag and script sources. HTML has no self-closing syntax for elements that hold
 * content: the parser reads {@code <span/>} as an open {@code <span>}, and whatever follows ends up inside it.
 * jQuery 3.5 stopped rewriting such tags in markup strings, which silently emptied the cluster page legends. Write
 * the end tag instead ({@code <span></span>}). Void elements ({@code <br/>}), SVG and JSP tags ({@code <c:out/>})
 * may self-close.
 */
public class SelfClosingTagTests {
	private static final Path WEBAPP = Paths.get("src/main/webapp");
	private static final Path VENDORED = WEBAPP.resolve("js/lib");

	private static final Set<String> VOID_ELEMENTS = Set.of("area", "base", "br", "col", "embed", "hr", "img",
			"input", "link", "meta", "param", "source", "track", "wbr");

	/** An HTML tag name (a prefixed JSP tag such as c:out never matches), its attributes, then "/>". */
	private static final Pattern SELF_CLOSED =
			Pattern.compile("<([a-zA-Z][a-zA-Z0-9]*)(?=[\\s/>])(?:[^<>\"']|\"[^\"]*\"|'[^']*')*/>");

	/** Text the browser never parses as HTML, or where self-closing is valid: comments, JSP code, SVG, MathML. */
	private static final Pattern SKIPPED = Pattern.compile(
			"<%--.*?--%>|<%.*?%>|<!--.*?-->|/\\*.*?\\*/|(?m:^[ \\t]*//[^\\n]*)|<svg\\b.*?</svg>|<math\\b.*?</math>",
			Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

	@Test
	public void webappSourcesCloseEveryNonVoidElement() throws IOException {
		List<Path> sources = sources();
		assertTrue(sources.contains(WEBAPP.resolve("js/explore/cluster.js")), "the scan must cover the scripts");
		assertTrue(sources.contains(WEBAPP.resolve("secure/add/job.jsp")), "the scan must cover the pages");

		List<String> found = new ArrayList<>();
		for (Path file : sources) {
			for (String hit : scan(new String(Files.readAllBytes(file), StandardCharsets.UTF_8))) {
				found.add(WEBAPP.relativize(file) + ":" + hit);
			}
		}
		assertEquals(Collections.emptyList(), found, "write an end tag (<span></span>), not <span/>");
	}

	@Test
	public void scanFlagsOnlyNonVoidHtmlElements() {
		String sample = String.join("\n",
				"$('#legend').html(\"<span class='list-count'/> Enqueued Job Pairs\");",
				"<option value=\"\"/>",
				"<br/> <img src=\"a.svg\" alt=\"\"/> <c:out value=\"${name}\"/> <span></span>",
				"<svg viewBox=\"0 0 1 1\"><path d=\"M0 0\"/></svg>",
				"<%-- <div/> --%> <!-- <div/> --> <% out.print(\"<div/>\"); %>",
				"\t// $(\"<div/>\") in a comment",
				"/* <div/> */ $(\"<div><p></p></div>\");");
		assertEquals(List.of("1: <span class='list-count'/>", "2: <option value=\"\"/>"), scan(sample));
	}

	/** Each self-closed non-void tag in one source text, as "line: tag". */
	private static List<String> scan(String source) {
		String text = blank(SKIPPED, source);
		List<String> found = new ArrayList<>();
		Matcher tag = SELF_CLOSED.matcher(text);
		while (tag.find()) {
			if (!VOID_ELEMENTS.contains(tag.group(1).toLowerCase(Locale.ROOT))) {
				found.add(lineOf(text, tag.start()) + ": " + tag.group());
			}
		}
		return found;
	}

	private static List<Path> sources() throws IOException {
		try (Stream<Path> files = Files.walk(WEBAPP)) {
			return files.filter(Files::isRegularFile)
					.filter(file -> !file.startsWith(VENDORED))
					.filter(file -> file.getFileName().toString().matches(".*\\.(jsp|tag|js)"))
					.sorted()
					.collect(Collectors.toList());
		}
	}

	/** Replaces each match with spaces, keeping line breaks so reported line numbers stay right. */
	private static String blank(Pattern pattern, String text) {
		return pattern.matcher(text).replaceAll(match -> match.group().replaceAll("[^\\n]", " "));
	}

	private static int lineOf(String text, int offset) {
		return (int) text.substring(0, offset).chars().filter(c -> c == '\n').count() + 1;
	}
}
