package org.starexec.backend;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.math.BigDecimal;

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

	/** Field name in the status record, so the readers cannot drift on the spelling either. */
	static final String FIELD = "stageNumber";

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
		if (status == null || !status.has(FIELD)) {
			throw invalid(source, "carries no " + FIELD
					+ ", so the stage that produced this result is unknown");
		}

		JsonElement raw = status.get(FIELD);
		if (raw.isJsonNull()) {
			throw invalid(source, "has a null " + FIELD);
		}
		// An array or an object reaches getAsInt() through gson's single-element unwrapping
		// or throws deep in the call; both are rejected here by shape instead.
		if (!raw.isJsonPrimitive() || !((JsonPrimitive) raw).isNumber()) {
			throw invalid(source, "has a non-numeric " + FIELD + " " + raw
					+ "; a stage identity is a JSON number, and a quoted or boolean value is"
					+ " not silently converted to one");
		}

		BigDecimal value;
		try {
			value = raw.getAsBigDecimal();
		} catch (NumberFormatException e) {
			throw invalid(source, "has a " + FIELD + " that is not a number: " + raw);
		}
		if (value.stripTrailingZeros().scale() > 0) {
			throw invalid(source, "has a fractional " + FIELD + " " + value
					+ "; truncating it would invent a stage");
		}
		if (value.compareTo(BigDecimal.valueOf(Integer.MIN_VALUE)) < 0
				|| value.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
			throw invalid(source, "has a " + FIELD + " of " + value
					+ ", which is outside the range a stage number can take; wrapping it would"
					+ " name a different stage entirely");
		}

		int stage = value.intValueExact();
		if (stage < 1) {
			throw invalid(source, "has " + FIELD + " " + stage
					+ ", and stage numbers start at 1, so it identifies no stage");
		}
		return stage;
	}

	private static StageStatusSnapshots.InvalidSnapshotException invalid(
			String source, String problem) {
		return new StageStatusSnapshots.InvalidSnapshotException(
				"the final status for " + source + " " + problem);
	}
}
