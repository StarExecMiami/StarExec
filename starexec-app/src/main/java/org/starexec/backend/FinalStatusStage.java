package org.starexec.backend;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.starexec.data.to.Status.StatusCode;

import java.math.BigDecimal;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * The stage identity carried by a pair's final {@code status.json}.
 *
 * <h2>Why this is shared</h2>
 *
 * The three monitors each read {@code stageNumber} with their own expression, and the three
 * expressions disagreed with each other on the same bytes. Measured against gson 2.10.1:
 * {@code null} threw in {@code LocalJobMonitor}, was swallowed into 1 by
 * {@code ContainerJobMonitor}, and became 1 in {@code KubernetesNativeBackend}; a JSON string
 * {@code "99"} was coerced to 99 everywhere; {@code 1.5} truncated to 1; a single-element
 * array {@code [1]} unwrapped to 1; and {@code 9999999999999} silently wrapped to
 * {@code 1316134911}. That is semantic drift between backends over the same protocol field,
 * so the parse lives in one place and every reader gets the same answer.
 *
 * <h2>What counts as a stage identity</h2>
 *
 * Present, a JSON number, integral, inside the {@code int} range, and at least 1. Nothing is
 * coerced and nothing is defaulted.
 *
 * <p>The readers used to substitute stage 1 for anything they could not use, which is how a
 * malformed file produced a confident precise stage write: {@code UpdatePairStatusPrecise}
 * gives the terminal status to that stage and NOT_REACHED to every stage above it, so an
 * invented 1 attributes the result to a stage that may not have run and marks the rest of the
 * pair not reached.
 *
 * <p>That default was never backward compatibility. {@code containerWriteStatus} has written
 * {@code stageNumber} unconditionally since the commit that introduced container mode
 * (2025-12-09); the readers' default was added three months later. No version of the producer
 * in this repository omits the field.
 *
 * <p>Reported as {@link StageStatusSnapshots.InvalidSnapshotException} because that is what
 * this is: content the job produced that cannot be believed and will read the same way on
 * every retry. {@code IngestionOutcome} already classifies it BLOCKED.
 */
final class FinalStatusStage {

	private FinalStatusStage() {
	}

	/** Field names in the status record, so the readers cannot drift on the spelling either. */
	static final String FIELD = "stageNumber";

	static final String STATUS_FIELD = "status";

	/**
	 * The stage this final status belongs to.
	 *
	 * @param status the parsed status record
	 * @param source what to name in the message -- a path, or a pair, whichever the caller has
	 * @return the stage number, guaranteed >= 1
	 * @throws StageStatusSnapshots.InvalidSnapshotException if the field is missing or is not a
	 *         stage identity
	 */
	static int require(JsonObject status, String source)
			throws StageStatusSnapshots.InvalidSnapshotException {
		int stage = requireInt(status, FIELD, source,
				"the stage that produced this result is unknown");
		if (stage < 1) {
			throw invalid(source, "has " + FIELD + " " + stage
					+ ", and stage numbers start at 1, so it identifies no stage");
		}
		return stage;
	}

	/**
	 * The status this final record reports.
	 *
	 * <p>Held to the same rules as the stage for the same reason: the readers used to
	 * substitute a default whenever they could not use the field, and for
	 * {@code KubernetesNativeBackend} that default was {@code STATUS_COMPLETE} -- so a
	 * truncated or malformed file recorded the pair as a successful solver run. A wrong stage
	 * misattributes a result; a wrong status invents one.
	 *
	 * <p>The value must also name a status the platform knows. An unrecognised code would
	 * otherwise be stored verbatim and read back as {@code STATUS_UNKNOWN} by everything
	 * downstream, which is the same fabrication one step later.
	 *
	 * @return the status code, guaranteed to resolve to a known {@link StatusCode}
	 */
	static int requireStatus(JsonObject status, String source)
			throws StageStatusSnapshots.InvalidSnapshotException {
		int code = requireInt(status, STATUS_FIELD, source,
				"the result this pair produced is unknown");
		if (StatusCode.toStatusCode(code) == StatusCode.STATUS_UNKNOWN
				&& code != StatusCode.STATUS_UNKNOWN.getVal()) {
			throw invalid(source, "has " + STATUS_FIELD + " " + code
					+ ", which is not a status this platform defines");
		}
		return code;
	}

	/**
	 * One field, read strictly: present, a JSON number, integral, and inside the {@code int}
	 * range. Nothing is coerced and nothing is defaulted.
	 */
	private static int requireInt(JsonObject record, String field, String source, String why)
			throws StageStatusSnapshots.InvalidSnapshotException {
		if (record == null || !record.has(field)) {
			throw invalid(source, "carries no " + field + ", so " + why);
		}

		JsonElement raw = record.get(field);
		if (raw.isJsonNull()) {
			throw invalid(source, "has a null " + field);
		}
		// An array or an object reaches getAsInt() through gson's single-element unwrapping
		// or throws deep in the call; both are rejected here by shape instead.
		if (!raw.isJsonPrimitive() || !((JsonPrimitive) raw).isNumber()) {
			throw invalid(source, "has a non-numeric " + field + " " + raw
					+ "; this is a JSON number, and a quoted or boolean value is not silently"
					+ " converted to one");
		}

		BigDecimal value;
		try {
			value = raw.getAsBigDecimal();
		} catch (NumberFormatException e) {
			throw invalid(source, "has a " + field + " that is not a number: " + raw);
		}
		if (value.stripTrailingZeros().scale() > 0) {
			throw invalid(source, "has a fractional " + field + " " + value
					+ "; truncating it would invent a value the producer did not write");
		}
		if (value.compareTo(BigDecimal.valueOf(Integer.MIN_VALUE)) < 0
				|| value.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
			throw invalid(source, "has a " + field + " of " + value
					+ ", which is outside the range this field can take; wrapping it would name"
					+ " something else entirely");
		}
		return value.intValueExact();
	}

	/**
	 * Whether this output directory shows that a run actually happened.
	 *
	 * <p>The question exists because {@code STATUS_COMPLETE} was a bare fallback: a container
	 * that exited 0 having produced nothing was recorded as a successful solver run, stamped
	 * with an {@code end_time} and a completion row, and became indistinguishable from a
	 * genuine result in every downstream query, ranking and export.
	 *
	 * <p>Evidence is an artifact only a run can leave:
	 *
	 * <ul>
	 *   <li>{@code var.out} / {@code watcher.out} — runsolver's own output, copied out by
	 *       {@code copyOutput};</li>
	 *   <li>{@code stats.json} — written by {@code updateStats} after the solver returns, and
	 *       written for <em>both</em> benchmarking frameworks, so it does not mistake a
	 *       BenchExec run for an empty one.</li>
	 * </ul>
	 *
	 * <p>{@code attributes.txt} is deliberately <em>not</em> in the list even though a
	 * post-processor cannot run without a solver. It is the one artifact
	 * {@code clearStaleAttemptArtifacts} does not remove, so a previous attempt's copy would
	 * survive into a rerun and make an empty attempt look like a completed one. It also adds
	 * nothing: {@code copyOutput} calls {@code updateStats} before the post-processor branch,
	 * so an {@code attributes.txt} always has a {@code stats.json} beside it.
	 *
	 * <p>The pair's log file is not evidence either. {@code log()} appends to it on every call
	 * from the first line of the job script, long before {@code initSandbox}, so it shows only
	 * that the script started -- which is exactly the case this has to catch.
	 *
	 * <p>An {@code IOException} is propagated rather than read as "no evidence". A directory
	 * that cannot be read is not a directory that is empty, and turning a storage fault into a
	 * verdict about a solver is the mistake this whole method exists to stop. Only
	 * {@link NoSuchFileException} means the artifact is genuinely absent.
	 *
	 * @param outputDir the directory to inspect; never null -- a caller that has no directory
	 *        has not looked, and must not claim it found nothing
	 */
	static boolean hasRunEvidence(Path outputDir) throws IOException {
		for (String artifact : RUN_ARTIFACTS) {
			try {
				Files.readAttributes(outputDir.resolve(artifact), BasicFileAttributes.class);
				return true;
			} catch (NoSuchFileException absent) {
				// Genuinely not there. Keep looking.
			}
		}
		return false;
	}

	/** Artifacts only a completed run leaves behind. Any one suffices. */
	private static final String[] RUN_ARTIFACTS = {"var.out", "watcher.out", "stats.json"};

	private static StageStatusSnapshots.InvalidSnapshotException invalid(
			String source, String problem) {
		return new StageStatusSnapshots.InvalidSnapshotException(
				"the final status for " + source + " " + problem);
	}
}
