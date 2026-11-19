package org.starexec.util;

import org.starexec.constants.R;

import java.io.File;
import java.util.Map;

/**
 * Where the picture of a user, solver or benchmark is stored: an original and a thumbnail
 * under {@code R.getPicturePath()}. The one place that knows the layout, so the upload that
 * writes a picture and the delete that removes it always name the same files.
 */
public final class PictureFiles {

	/** The picture types, as the upload form and the delete endpoint name them. */
	public static final String USER = "user";
	public static final String SOLVER = R.SOLVER;
	public static final String BENCHMARK = "benchmark";

	private static final Map<String, String> DIRECTORY = Map.of(
			USER, "users",
			SOLVER, "solvers",
			BENCHMARK, "benchmarks"
	);

	private PictureFiles() {
	}

	/** Whether {@code type} names a kind of entity that has a picture. */
	public static boolean isKnownType(String type) {
		return type != null && DIRECTORY.containsKey(type);
	}

	/** The stored original picture of the entity. */
	public static File original(String type, int id) {
		return file(type, id, "_org.jpg");
	}

	/** The stored thumbnail of the entity. */
	public static File thumbnail(String type, int id) {
		return file(type, id, "_thn.jpg");
	}

	private static File file(String type, int id, String suffix) {
		String directory = DIRECTORY.get(type);
		if (directory == null) {
			throw new IllegalArgumentException("not a picture type: " + type);
		}
		return new File(R.getPicturePath() + File.separator + directory + File.separator + "Pic" + id + suffix);
	}
}
