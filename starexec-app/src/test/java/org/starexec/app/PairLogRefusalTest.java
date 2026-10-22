package org.starexec.app;

import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.data.database.JobPairs;
import org.starexec.data.security.JobSecurity;
import org.starexec.data.security.ValidatorStatusCode;
import org.starexec.util.SessionUtil;
import org.starexec.util.Validator;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;

/**
 * A refused pair log must read exactly like a missing one.
 *
 * <p>The REST layer answers every error with HTTP 200 and "not available" (see RESTException), and the
 * pair log endpoint already did so for a missing log. A refusal got its own text instead, which named
 * the pair's id as a job's, so a client could neither recognize the refusal nor tell it apart from a log.
 */
public class PairLogRefusalTest {

	private static final int PAIR_ID = 537;

	@BeforeClass
	public static void initialize() {
		Validator.initialize();
	}

	/** Requests the log of a pair that has none, as a user who may or may not see it. */
	private static Response answerWithoutLog(boolean canSee) {
		try (MockedStatic<SessionUtil> session = Mockito.mockStatic(SessionUtil.class);
				MockedStatic<JobSecurity> security = Mockito.mockStatic(JobSecurity.class);
				MockedStatic<JobPairs> pairs = Mockito.mockStatic(JobPairs.class)) {
			session.when(() -> SessionUtil.getUserId(any())).thenReturn(7);
			security.when(() -> JobSecurity.canUserSeeJobWithPair(anyInt(), anyInt()))
					.thenReturn(new ValidatorStatusCode(canSee));
			pairs.when(() -> JobPairs.getJobLog(anyInt())).thenReturn(null);
			try {
				String log = new RESTServices().getJobPairLog(PAIR_ID, Mockito.mock(HttpServletRequest.class));
				fail("expected the answer to a missing log, got a log: " + log);
				return null;
			} catch (WebApplicationException e) {
				if (!canSee) {
					// Nothing is read for a user who may not see the pair.
					pairs.verify(() -> JobPairs.getJobLog(anyInt()), Mockito.never());
				}
				return e.getResponse();
			}
		}
	}

	@Test
	public void aRefusedLogReadsExactlyLikeAMissingLog() {
		Response refused = answerWithoutLog(false);
		Response missing = answerWithoutLog(true);
		assertEquals(missing.getStatus(), refused.getStatus());
		assertEquals(missing.getEntity(), refused.getEntity());
		assertEquals("not available", refused.getEntity());
	}
}
