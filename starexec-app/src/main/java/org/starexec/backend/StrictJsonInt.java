package org.starexec.backend;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.math.BigDecimal;

/**
 * An integer field of a record the job wrote, read without coercion.
 *
 * <p>{@link JsonElement#getAsInt()} converts rather than validates: the string {@code "99"}
 * becomes 99, {@code 1.5} truncates to 1, the one-element array {@code [1]} unwraps to 1, and
 * {@code 9999999999999} wraps to {@code 1316134911}. Every one of those names a pair, a stage or
 * a status the producer did not write. The pair's output directory is writable by the solver, so
 * these fields are claims to be checked, not values to be repaired.
 *
 * <p>Accepted: a JSON number with an integral value inside the {@code int} range. {@code 1.0}
 * is integral and is accepted; nothing else is converted.
 *
 * <p>Presence is the caller's to judge, because what a missing field means differs by record.
 * The reason a value is refused is carried as a fragment ("has a fractional stageNumber 1.5; ...")
 * that each caller places after the name of the file or pair it was reading.
 */
final class StrictJsonInt {

	private StrictJsonInt() {
	}

	/** Why a present field is not an integer. The message is a fragment, not a sentence. */
	static final class NotAnInt extends Exception {

		private static final long serialVersionUID = 1L;

		NotAnInt(String problem) {
			super(problem);
		}
	}

	/**
	 * @param field the field's name, for the message
	 * @param raw   the field's value as parsed; the caller has established that it is present
	 * @return the value, exactly as written
	 * @throws NotAnInt when the value is null, not a JSON number, fractional or out of range
	 */
	static int parse(String field, JsonElement raw) throws NotAnInt {
		if (raw == null || raw.isJsonNull()) {
			throw new NotAnInt("has a null " + field);
		}
		// An array or an object reaches getAsInt() through gson's single-element unwrapping
		// or throws deep in the call; both are rejected here by shape instead.
		if (!raw.isJsonPrimitive() || !((JsonPrimitive) raw).isNumber()) {
			throw new NotAnInt("has a non-numeric " + field + " " + raw
					+ "; this is a JSON number, and a quoted or boolean value is not silently"
					+ " converted to one");
		}

		BigDecimal value;
		try {
			value = raw.getAsBigDecimal();
		} catch (NumberFormatException e) {
			throw new NotAnInt("has a " + field + " that is not a number: " + raw);
		}
		if (value.stripTrailingZeros().scale() > 0) {
			throw new NotAnInt("has a fractional " + field + " " + value
					+ "; truncating it would invent a value the producer did not write");
		}
		if (value.compareTo(BigDecimal.valueOf(Integer.MIN_VALUE)) < 0
				|| value.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
			throw new NotAnInt("has a " + field + " of " + value
					+ ", which is outside the range this field can take; wrapping it would name"
					+ " something else entirely");
		}
		return value.intValueExact();
	}
}
