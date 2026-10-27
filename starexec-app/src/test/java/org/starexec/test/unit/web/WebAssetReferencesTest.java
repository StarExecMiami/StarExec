package org.starexec.test.unit.web;

import org.junit.Test;
import org.starexec.constants.Web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Guards the web assets that CI cannot otherwise check: the build skips Sass
 * (skip.sass), so a broken image path or an unknown icon name would only show
 * up as a missing picture, or fail the container build.
 */
public class WebAssetReferencesTest {

	private static final Path WEBAPP = Path.of("src/main/webapp");
	private static final Path ICON_SOURCES = Path.of("src/main/icons/lucide");
	private static final Path ICON_DATA = WEBAPP.resolve("css/global/_icon-data.scss");
	private static final Path UI_ICON_MAP = WEBAPP.resolve("css/components/_ui-icons.scss");

	/** Third-party code and themes, kept as shipped. */
	private static final List<Path> VENDORED = List.of(
		WEBAPP.resolve("js/lib"), WEBAPP.resolve("css/jqueryui"), WEBAPP.resolve("css/jstree"),
		WEBAPP.resolve("css/datatables"), WEBAPP.resolve("css/prettify"), WEBAPP.resolve("css/explore/jquery.qtip.css"));

	/** Icon classes jQuery UI 1.13 widgets add by themselves (dialog, datepicker, menu, resizable). */
	private static final Set<String> JQUERY_UI_INTERNAL_ICONS = Set.of(
		"caret-1-e", "check", "circle-triangle-e", "circle-triangle-w", "closethick",
		"gripsmall-diagonal-se", "triangle-1-e", "triangle-1-n", "triangle-1-s");

	/** ui-icon-* classes that are not icons: state and layout modifiers, and the deliberate empty icon. */
	private static final Set<String> NOT_ICONS = Set.of("background", "blank", "notext");

	/** Group 1: any relative prefix (e.g. "../../" or "jqueryui/"); group 2: the path below images/. */
	private static final Pattern IMAGE_REFERENCE =
		Pattern.compile("((?:[A-Za-z0-9_.-]+/)*)images/([A-Za-z0-9_./-]+\\.(?:png|gif|jpe?g|svg|ico|webp))");
	private static final Pattern UI_ICON_CLASS = Pattern.compile("ui-icon-([a-z0-9]+(?:-[a-z0-9]+)*)");
	private static final Pattern MAP_ENTRY = Pattern.compile("\"([a-z0-9-]+)\":\\s*\"([a-z0-9-]+)\"");
	private static final Pattern ICON_CALL = Pattern.compile("icon(?:-mask)?\\(\"([a-z0-9-]+)\"\\)");
	private static final Pattern ICON_DATA_KEY = Pattern.compile("(?m)^\\t\"([a-z0-9-]+)\": url\\(");

	@Test
	public void everyImageTheWebappReferencesExists() throws IOException {
		List<String> missing = new ArrayList<>();
		for (Path file : sourceFiles(WEBAPP, ".jsp", ".tag", ".js", ".scss", ".html")) {
			Matcher m = IMAGE_REFERENCE.matcher(Files.readString(file));
			while (m.find()) {
				if (!imageExists(file, m.group(1), m.group(2))) {
					missing.add(file + " -> " + m.group());
				}
			}
		}
		assertTrue("Image references with no file behind them:\n" + String.join("\n", missing), missing.isEmpty());
	}

	@Test
	public void everyImageTheOfflineJobPageBundlesExists() {
		List<String> missing = new ArrayList<>();
		checkBundled(Web.GLOBAL_SVG_FILES, "svg", missing);
		checkBundled(Web.GLOBAL_ICO_FILES, "ico", missing);
		assertTrue("DoJobPage would fail to copy:\n" + String.join("\n", missing), missing.isEmpty());
	}

	@Test
	public void everyJqueryUiIconInUseIsMapped() throws IOException {
		Set<String> used = new TreeSet<>(JQUERY_UI_INTERNAL_ICONS);
		List<Path> files = new ArrayList<>(sourceFiles(WEBAPP, ".jsp", ".tag", ".js", ".html"));
		files.addAll(sourceFiles(Path.of("src/main/java"), ".java"));
		for (Path file : files) {
			Matcher m = UI_ICON_CLASS.matcher(Files.readString(file));
			while (m.find()) {
				used.add(m.group(1));
			}
		}
		used.removeAll(NOT_ICONS);
		used.removeAll(uiIconMap().keySet());
		assertTrue("ui-icon-* classes with no entry in " + UI_ICON_MAP + ": " + used, used.isEmpty());
	}

	@Test
	public void everyIconNameUsedInStylesIsVendored() throws IOException {
		Set<String> names = new TreeSet<>(uiIconMap().values());
		for (Path file : sourceFiles(WEBAPP.resolve("css"), ".scss")) {
			Matcher m = ICON_CALL.matcher(Files.readString(file));
			while (m.find()) {
				names.add(m.group(1));
			}
		}
		names.removeAll(vendoredIcons());
		assertTrue("Icons used in SCSS but missing from " + ICON_SOURCES + ": " + names, names.isEmpty());
	}

	@Test
	public void generatedIconDataMatchesTheVendoredIcons() throws IOException {
		Set<String> generated = new TreeSet<>();
		Matcher m = ICON_DATA_KEY.matcher(Files.readString(ICON_DATA));
		while (m.find()) {
			generated.add(m.group(1));
		}
		assertFalse("No icons found in " + ICON_DATA, generated.isEmpty());
		assertEquals("Rerun scripts/build-icon-data.py", vendoredIcons(), generated);
	}

	/**
	 * A reference resolves from the site root (${starexecRoot}/images/...), relative to the
	 * file itself, or, for SCSS partials compiled into css/global.css, relative to css/.
	 */
	private static boolean imageExists(Path file, String prefix, String name) {
		if (Files.isRegularFile(WEBAPP.resolve("images").resolve(name))) {
			return true;
		}
		Path relative = Path.of(prefix + "images/" + name);
		return Files.isRegularFile(file.getParent().resolve(relative).normalize())
			|| Files.isRegularFile(WEBAPP.resolve("css").resolve(relative).normalize());
	}

	private static void checkBundled(String[] names, String extension, List<String> missing) {
		for (String name : names) {
			Path file = WEBAPP.resolve("images").resolve(name + "." + extension);
			if (!Files.isRegularFile(file)) {
				missing.add(file.toString());
			}
		}
	}

	private static Map<String, String> uiIconMap() throws IOException {
		String scss = Files.readString(UI_ICON_MAP);
		int start = scss.indexOf("$ui-icons: (");
		assertTrue("No $ui-icons map in " + UI_ICON_MAP, start >= 0);
		String block = scss.substring(start, scss.indexOf(");", start));
		Map<String, String> map = new TreeMap<>();
		Matcher m = MAP_ENTRY.matcher(block);
		while (m.find()) {
			map.put(m.group(1), m.group(2));
		}
		return map;
	}

	private static Set<String> vendoredIcons() throws IOException {
		try (Stream<Path> files = Files.list(ICON_SOURCES)) {
			return files.map(p -> p.getFileName().toString())
				.filter(n -> n.endsWith(".svg"))
				.map(n -> n.substring(0, n.length() - ".svg".length()))
				.collect(Collectors.toCollection(TreeSet::new));
		}
	}

	private static List<Path> sourceFiles(Path root, String... extensions) throws IOException {
		try (Stream<Path> files = Files.walk(root)) {
			return files.filter(Files::isRegularFile)
				.filter(p -> VENDORED.stream().noneMatch(p::startsWith))
				.filter(p -> Stream.of(extensions).anyMatch(e -> p.toString().endsWith(e)))
				.collect(Collectors.toList());
		}
	}
}
