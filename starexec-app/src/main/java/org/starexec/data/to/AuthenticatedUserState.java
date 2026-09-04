package org.starexec.data.to;

/**
 * The authoritative account state for one authenticated request, read fresh
 * from the database instead of trusted from the servlet session.
 *
 * <p>The five states are deliberately distinct. Collapsing any pair of them
 * reintroduces a defect this type exists to prevent:</p>
 *
 * <ul>
 *   <li>{@code ABSENT} must never be produced by a failure. A database outage
 *       that reported "user is gone" would invalidate every live session in the
 *       system the moment the database blinked.</li>
 *   <li>{@code ERROR} must never be treated as {@code ACTIVE}. A lookup that
 *       could not be completed has not established that the caller is still
 *       entitled to anything, so the request fails closed.</li>
 *   <li>{@code MALFORMED} must not be reported as {@code ABSENT}. The account
 *       row exists; its role data is unusable. Invalidating the session would
 *       tell the operator the user was deleted, which is false.</li>
 *   <li>{@code DENIED} must not be reported as {@code ABSENT}. A suspended user
 *       is still an account, and the existing product behaviour returns them to
 *       the index page rather than logging them out.</li>
 * </ul>
 *
 * <p>Instances are immutable and are never stored in the session; a fresh one is
 * produced per request.</p>
 */
public final class AuthenticatedUserState {

	public enum State {
		/** The account exists, is well formed, and may proceed. */
		ACTIVE,
		/** The account exists and is well formed, but its role forbids use of the system. */
		DENIED,
		/** No row for this id exists. Established by a successful query, never by a failure. */
		ABSENT,
		/** The row exists but its role data violates the single-role model. */
		MALFORMED,
		/** The state could not be established. Says nothing about whether the account exists. */
		ERROR
	}

	private final State state;
	private final User user;
	private final String role;
	private final String diagnostic;

	private AuthenticatedUserState(State state, User user, String role, String diagnostic) {
		this.state = state;
		this.user = user;
		this.role = role;
		this.diagnostic = diagnostic;
	}

	public static AuthenticatedUserState active(User user, String role) {
		return new AuthenticatedUserState(State.ACTIVE, user, role, null);
	}

	public static AuthenticatedUserState denied(User user, String role) {
		return new AuthenticatedUserState(State.DENIED, user, role, null);
	}

	public static AuthenticatedUserState absent() {
		return new AuthenticatedUserState(State.ABSENT, null, null, null);
	}

	public static AuthenticatedUserState malformed(String diagnostic) {
		return new AuthenticatedUserState(State.MALFORMED, null, null, diagnostic);
	}

	public static AuthenticatedUserState error(String diagnostic) {
		return new AuthenticatedUserState(State.ERROR, null, null, diagnostic);
	}

	public State getState() {
		return state;
	}

	/** The freshly constructed user, non-null only for ACTIVE and DENIED. */
	public User getUser() {
		return user;
	}

	/** The single validated role, non-null only for ACTIVE and DENIED. */
	public String getRole() {
		return role;
	}

	/** Why the state could not be established; null for ACTIVE, DENIED and ABSENT. */
	public String getDiagnostic() {
		return diagnostic;
	}

	@Override
	public String toString() {
		return "AuthenticatedUserState[" + state + (role == null ? "" : ", role=" + role)
				+ (diagnostic == null ? "" : ", " + diagnostic) + "]";
	}
}
