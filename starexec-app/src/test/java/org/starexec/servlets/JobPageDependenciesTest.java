package org.starexec.servlets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.starexec.constants.R;
import org.starexec.constants.Web;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobPageDependenciesTest {
	@TempDir
	Path temporary;

	@Test
	void offlineExportCopiesTreeAssetsWhenSourceMapsAreAbsent() throws Exception {
		Path web = temporary.resolve("web");
		Path export = temporary.resolve("export");
		writeAssets(web, "js", "js", Web.JOB_DETAILS_JS_FILES);
		writeAssets(web, "js", "js", Web.JS_FILES_FOR_LOCAL_JOB);
		writeAssets(web, "css", "css", Web.JOB_DETAILS_CSS_FILES);
		writeAssets(web, "css", "css", Web.CSS_FILES_FOR_LOCAL_JOB);
		writeAssets(web, "images", "svg", Web.GLOBAL_SVG_FILES);
		writeAssets(web, "images", "ico", Web.GLOBAL_ICO_FILES);
		write(web.resolve("css/details/job.css.map"));
		write(web.resolve("css/jqueryui/images/icon.png"));
		write(web.resolve("css/jstree/default-dark/style.min.css"));
		write(web.resolve("css/jstree/default-dark/throbber.gif"));
		write(web.resolve("js/lib/jstree/LICENSE-MIT"));

		String originalRoot = R.STAREXEC_ROOT;
		try {
			R.STAREXEC_ROOT = web.toString() + File.separator;
			var copy = DoJobPage.class.getDeclaredMethod("doPageDependencies", File.class);
			copy.setAccessible(true);
			copy.invoke(null, export.toFile());
		} finally {
			R.STAREXEC_ROOT = originalRoot;
		}

		assertFalse(Files.exists(export.resolve("css/global.css.map")));
		assertTrue(Files.isRegularFile(export.resolve("css/details/job.css.map")));
		assertTrue(Files.isRegularFile(export.resolve("js/lib/jstree.min.js")));
		assertTrue(Files.isRegularFile(export.resolve("js/util/tree.js")));
		assertTrue(Files.isRegularFile(export.resolve("js/lib/jstree/LICENSE-MIT")));
		assertTrue(Files.isRegularFile(export.resolve("css/jstree/default-dark/style.min.css")));
		assertTrue(Files.isRegularFile(export.resolve("css/jstree/default-dark/throbber.gif")));
	}

	private static void writeAssets(Path root, String directory, String extension, String[] entries) throws IOException {
		for (String entry : entries) {
			write(root.resolve(directory).resolve(entry + "." + extension));
		}
	}

	private static void write(Path file) throws IOException {
		Files.createDirectories(file.getParent());
		Files.writeString(file, "fixture");
	}
}
