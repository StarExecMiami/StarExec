package org.starexec.data.to.enums;

import org.starexec.util.Util;
import org.starexec.util.XMLUtil;

/**
 * Created by agieg on 11/14/2016.
 */
public enum JobXmlType {

	STANDARD("batchJobSchema.xsd"), SOLVER_UPLOAD("runSolverOnUploadBatchJobSchema.xsd");

	/**
	 * Where a reader can fetch this schema from this instance.
	 *
	 * <p>Also what {@link XMLUtil#validateAgainstSchema} is given, which resolves it by file
	 * name against the bundled copies rather than fetching it.
	 */
	public final String schemaPath;

	/**
	 * The namespace documents of this type are written in.
	 *
	 * <p>Separate from {@link #schemaPath} because the two are different things: a namespace
	 * identifies the vocabulary and is the same everywhere, while the path above depends on
	 * where this instance is deployed. Conflating them is what made an exported job document
	 * unreadable by any other instance (#122).
	 */
	public final String namespace;

	JobXmlType(String schemaName) {
		this.schemaPath = Util.url("public/" + schemaName);
		this.namespace = XMLUtil.SCHEMA_NAMESPACE_ROOT + schemaName;
	}
}
