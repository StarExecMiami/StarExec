package org.starexec.exceptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Raised when user deletion is refused for a reason an administrator can act
 * on, as opposed to an unexpected failure.
 *
 * <p>The layer that knows WHY deletion is blocked constructs this and supplies
 * structured, already-safe fields. Callers must build any user-facing text from
 * those fields; they must not parse a message, and they must never surface the
 * underlying cause, which may carry driver or SQL detail.</p>
 *
 * <p>It extends {@link StarExecDatabaseException} so existing signatures and
 * teardown handlers keep working unchanged; callers that want the structured
 * detail simply catch this narrower type first.</p>
 */
public class UserDeletionBlockedException extends StarExecDatabaseException {

	/** More than one candidate personal space matched, so no subtree can be chosen. */
	public static final String AMBIGUOUS_PERSONAL_SPACE = "AMBIGUOUS_PERSONAL_SPACE";

	private final String reasonCode;
	private final List<Integer> blockingSpaceIds;
	private final List<String> blockingSpaceNames;

	public UserDeletionBlockedException(String reasonCode, String message,
			List<Integer> blockingSpaceIds, List<String> blockingSpaceNames, Throwable cause) {
		super(message, cause);
		this.reasonCode = reasonCode;
		this.blockingSpaceIds = blockingSpaceIds == null
				? Collections.emptyList() : new ArrayList<>(blockingSpaceIds);
		this.blockingSpaceNames = blockingSpaceNames == null
				? Collections.emptyList() : new ArrayList<>(blockingSpaceNames);
	}

	public UserDeletionBlockedException(String reasonCode, String message,
			List<Integer> blockingSpaceIds, List<String> blockingSpaceNames) {
		this(reasonCode, message, blockingSpaceIds, blockingSpaceNames, null);
	}

	public String getReasonCode() {
		return reasonCode;
	}

	public List<Integer> getBlockingSpaceIds() {
		return Collections.unmodifiableList(blockingSpaceIds);
	}

	public List<String> getBlockingSpaceNames() {
		return Collections.unmodifiableList(blockingSpaceNames);
	}
}
