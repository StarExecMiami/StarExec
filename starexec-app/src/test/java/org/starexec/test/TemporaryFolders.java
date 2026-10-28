package org.starexec.test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Creates folders and files under a JUnit Jupiter {@code @TempDir} with the semantics of JUnit 4's
 * {@code TemporaryFolder} rule, which these tests were written against: paths must be relative,
 * {@link #newFolder(File)} returns a new uniquely named folder, and creating a folder or file that
 * already exists fails.
 */
public final class TemporaryFolders {
	private TemporaryFolders() {
	}

	/** A new folder with a unique name in {@code root}, like {@code TemporaryFolder.newFolder()}. */
	public static File newFolder(File root) throws IOException {
		return Files.createTempDirectory(root.toPath(), "junit").toFile();
	}

	/**
	 * Creates {@code root/paths[0]/paths[1]/...}, like {@code TemporaryFolder.newFolder(String...)}.
	 *
	 * @throws IOException if a path is absolute, a file is in the way, or the last folder already
	 *         exists
	 */
	public static File newFolder(File root, String... paths) throws IOException {
		if (paths.length == 0) {
			throw new IllegalArgumentException("must pass at least one path");
		}
		for (String path : paths) {
			if (new File(path).isAbsolute()) {
				throw new IOException("folder path '" + path + "' is not a relative path");
			}
		}
		File relative = null;
		File folder = root;
		boolean created = true;
		for (String path : paths) {
			relative = new File(relative, path);
			folder = new File(root, relative.getPath());
			created = folder.mkdirs();
			if (!created && !folder.isDirectory()) {
				throw new IOException(folder.exists()
						? "a file with the path '" + relative.getPath() + "' exists"
						: "could not create a folder with the path '" + relative.getPath() + "'");
			}
		}
		if (!created) {
			throw new IOException("a folder with the path '" + relative.getPath() + "' already exists");
		}
		return folder;
	}

	/**
	 * Creates the empty file {@code root/name}, like {@code TemporaryFolder.newFile(String)}.
	 *
	 * @throws IOException if the file already exists
	 */
	public static File newFile(File root, String name) throws IOException {
		File file = new File(root, name);
		if (!file.createNewFile()) {
			throw new IOException("a file with the name '" + name + "' already exists in the test folder");
		}
		return file;
	}
}
