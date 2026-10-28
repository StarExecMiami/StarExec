package org.starexec.util;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.apache.commons.io.FileUtils;
import org.apache.commons.mail.EmailException;
import org.apache.commons.mail.SimpleEmail;
import org.junit.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.invocation.Invocation;
import org.slf4j.LoggerFactory;
import org.starexec.data.database.ErrorLogs;
import org.starexec.data.database.RunscriptErrors;
import org.starexec.data.database.Spaces;
import org.starexec.data.to.CommunityRequest;
import org.starexec.data.to.User;
import org.starexec.logger.StarLevel;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

/**
 * One address that cannot be used must not stop a mailing to the others (ledger M3).
 *
 * <p>Mail put every recipient on one message. The seeded admin account's address is the bare word
 * {@code admin}, which commons-email refuses, so on a community the admin leads, the request to join it
 * reached no leader, and the log said it had been sent. No socket is opened here: SimpleEmail is mocked,
 * an address is unusable when addTo() throws for it, as commons-email's does for {@code admin} (the first
 * test pins that with the real class), and a message is refused when send() throws, as it does when the
 * SMTP server refuses a recipient.
 */
public class MailPerRecipientTest {

	private static final String UNUSABLE = "admin";
	private static final String ALICE = "synthetic-leader-a@example.invalid";
	private static final String BOB = "synthetic-leader-b@example.invalid";
	private static final String CAROL = "synthetic-leader-c@example.invalid";
	private static final List<String> SYNTHETIC = List.of(ALICE, BOB, CAROL);
	private static final int COMMUNITY = 7;
	private static final int REQUESTER = 42;

	/** The address from the field fails in commons-email itself, before any SMTP traffic. */
	@Test
	public void commonsEmailRefusesTheSeededAdminAddress() {
		assertThrows(EmailException.class, () -> new SimpleEmail().addTo(UNUSABLE));
	}

	@Test
	public void mailCountsTheAddressesTheServerAcceptedAMessageFor() {
		List<List<String>> accepted = new ArrayList<>();
		try (MockedStatic<ErrorLogs> persisted = quietErrorLogs();
		     MockedConstruction<SimpleEmail> emails = transport(Set.of(UNUSABLE), Set.of(CAROL), accepted)) {
			assertEquals(2, Mail.mail("body", "subject", List.of(ALICE, UNUSABLE, BOB, CAROL)));
			assertEquals(List.of(List.of(ALICE), List.of(BOB)), accepted);
		}
	}

	@Test
	public void mailWithNoAddressesBuildsNoMessage() {
		try (MockedConstruction<SimpleEmail> emails = mockConstruction(SimpleEmail.class)) {
			assertEquals(0, Mail.mail("body", "subject", (Collection<String>) null));
			assertEquals(0, Mail.mail("body", "subject", List.of()));
			assertTrue(emails.constructed().isEmpty());
		}
	}

	@Test
	public void aJoinRequestReachesEveryLeaderWhoseAddressWorks() throws Exception {
		List<List<String>> accepted = new ArrayList<>();
		try (JoinRequestStatics statics = new JoinRequestStatics(leaders(UNUSABLE, ALICE, BOB));
		     LogCapture logs = new LogCapture();
		     MockedConstruction<SimpleEmail> emails = transport(Set.of(UNUSABLE), Set.of(), accepted)) {
			Mail.sendCommunityRequest(requester(), request());
			assertEquals(Set.of(ALICE, BOB), recipients(accepted));
			logs.assertNoSyntheticAddress();
		}
	}

	@Test
	public void eachLeaderGetsTheirOwnMessage() throws Exception {
		List<List<String>> accepted = new ArrayList<>();
		try (JoinRequestStatics statics = new JoinRequestStatics(leaders(ALICE, BOB));
		     LogCapture logs = new LogCapture();
		     MockedConstruction<SimpleEmail> emails = transport(Set.of(), Set.of(), accepted)) {
			Mail.sendCommunityRequest(requester(), request());
			assertEquals("one accepted message per leader, each addressed to one leader",
					List.of(List.of(ALICE), List.of(BOB)), accepted);
		}
	}

	@Test
	public void aRefusedLeaderDoesNotStopTheLeadersAfterIt() throws Exception {
		List<List<String>> accepted = new ArrayList<>();
		try (JoinRequestStatics statics = new JoinRequestStatics(leaders(ALICE, BOB, CAROL));
		     LogCapture logs = new LogCapture();
		     MockedConstruction<SimpleEmail> emails = transport(Set.of(), Set.of(BOB), accepted)) {
			Mail.sendCommunityRequest(requester(), request());
			assertEquals(Set.of(ALICE, CAROL), recipients(accepted));
			logs.assertNoSyntheticAddress();
		}
	}

	@Test
	public void aPartlyDeliveredJoinRequestIsLoggedAsAWarningWithTheCounts() throws Exception {
		try (JoinRequestStatics statics = new JoinRequestStatics(leaders(UNUSABLE, ALICE));
		     LogCapture logs = new LogCapture();
		     MockedConstruction<SimpleEmail> emails = transport(Set.of(UNUSABLE), Set.of(), new ArrayList<>())) {
			Mail.sendCommunityRequest(requester(), request());
			logs.assertOne(Level.WARN, "community [" + COMMUNITY + "]", "user [" + REQUESTER + "]",
					"emailed to 1 of its 2 leader(s)");
			logs.assertNoSyntheticAddress();
		}
	}

	@Test
	public void aJoinRequestThatReachedNoLeaderIsNotLoggedAsSent() throws Exception {
		try (JoinRequestStatics statics = new JoinRequestStatics(leaders(UNUSABLE));
		     LogCapture logs = new LogCapture();
		     MockedConstruction<SimpleEmail> emails = transport(Set.of(UNUSABLE), Set.of(), new ArrayList<>())) {
			Mail.sendCommunityRequest(requester(), request());
			logs.assertOne(Level.WARN, "community [" + COMMUNITY + "]", "emailed to 0 of its 1 leader(s)");
			logs.assertNoneAtOrAbove(Level.INFO, " sent to leaders");
		}
	}

	@Test
	public void aJoinRequestThatReachedEveryLeaderIsLoggedAtInfo() throws Exception {
		try (JoinRequestStatics statics = new JoinRequestStatics(leaders(ALICE, BOB));
		     LogCapture logs = new LogCapture();
		     MockedConstruction<SimpleEmail> emails = transport(Set.of(), Set.of(), new ArrayList<>())) {
			Mail.sendCommunityRequest(requester(), request());
			logs.assertOne(Level.INFO, "community [" + COMMUNITY + "]", "emailed to 2 of its 2 leader(s)");
			assertTrue("nothing failed, so nothing may warn", logs.at(Level.WARN).isEmpty());
		}
	}

	@Test
	public void aJoinRequestToACommunityWithoutLeadersIsReported() throws Exception {
		try (JoinRequestStatics statics = new JoinRequestStatics(List.of());
		     LogCapture logs = new LogCapture();
		     MockedConstruction<SimpleEmail> emails = transport(Set.of(), Set.of(), new ArrayList<>())) {
			Mail.sendCommunityRequest(requester(), request());
			assertTrue("no leader, so no message may be built", emails.constructed().isEmpty());
			logs.assertOne(Level.WARN, "community [" + COMMUNITY + "]", "no leader");
		}
	}

	/** Spaces.getLeaders returns null when it cannot read the leaders; the request must not throw. */
	@Test
	public void aJoinRequestWhoseLeadersCannotBeReadIsReported() throws Exception {
		try (JoinRequestStatics statics = new JoinRequestStatics(null);
		     LogCapture logs = new LogCapture();
		     MockedConstruction<SimpleEmail> emails = transport(Set.of(), Set.of(), new ArrayList<>())) {
			Mail.sendCommunityRequest(requester(), request());
			assertTrue(emails.constructed().isEmpty());
			logs.assertOne(Level.WARN, "community [" + COMMUNITY + "]", "could not read");
		}
	}

	@Test
	public void theErrorReportReachesEverySubscriberWhoseAddressWorks() throws Exception {
		List<List<String>> accepted = new ArrayList<>();
		try (MockedStatic<RunscriptErrors> errors = mockStatic(RunscriptErrors.class);
		     MockedStatic<ErrorLogs> persisted = quietErrorLogs();
		     LogCapture logs = new LogCapture();
		     MockedConstruction<SimpleEmail> emails = transport(Set.of(UNUSABLE), Set.of(), accepted)) {
			errors.when(() -> RunscriptErrors.getCount(any(), any())).thenReturn(0);
			Mail.sendErrorLogEmails(List.of(), leaders(UNUSABLE, ALICE));
			assertEquals(List.of(List.of(ALICE)), accepted);
			logs.assertOne(Level.WARN, "emailed to 1 of 2 recipient(s)");
			logs.assertNoSyntheticAddress();
		}
	}

	/**
	 * Mocked messages. addTo() throws for an unusable address, as commons-email's does. send() throws when
	 * the message carries a refused address, as an SMTP refusal does; otherwise it records the message's
	 * recipients in {@code accepted}, one entry per accepted message.
	 */
	private static MockedConstruction<SimpleEmail> transport(Set<String> unusable, Set<String> refused,
			List<List<String>> accepted) {
		return mockConstruction(SimpleEmail.class, (mock, context) -> {
			for (String address : unusable) {
				when(mock.addTo(address)).thenThrow(new EmailException("synthetic: Missing final '@domain'"));
			}
			when(mock.send()).thenAnswer(invocation -> {
				List<String> to = addressesOf(mock);
				if (to.stream().anyMatch(refused::contains)) {
					throw new EmailException("synthetic refusal");
				}
				accepted.add(to);
				return "synthetic-message-id";
			});
		});
	}

	/** The addresses addTo() was called with on one mocked message, in call order. */
	private static List<String> addressesOf(SimpleEmail mail) {
		return mockingDetails(mail).getInvocations().stream()
				.filter(i -> i.getMethod().getName().equals("addTo") && i.getArguments().length == 1)
				.map(Invocation::getArguments)
				.map(arguments -> (String) arguments[0])
				.collect(Collectors.toList());
	}

	private static Set<String> recipients(List<List<String>> accepted) {
		return accepted.stream().flatMap(List::stream).collect(Collectors.toSet());
	}

	private static List<User> leaders(String... addresses) {
		List<User> users = new ArrayList<>();
		int id = 100;
		for (String address : addresses) {
			User user = mock(User.class);
			when(user.getId()).thenReturn(id++);
			when(user.getEmail()).thenReturn(address);
			users.add(user);
		}
		return users;
	}

	private static User requester() {
		User user = mock(User.class);
		when(user.getId()).thenReturn(REQUESTER);
		when(user.getFullName()).thenReturn("Synthetic Requester");
		when(user.getEmail()).thenReturn("synthetic-requester@example.invalid");
		when(user.getInstitution()).thenReturn("Synthetic Institution");
		return user;
	}

	private static CommunityRequest request() {
		CommunityRequest request = new CommunityRequest();
		request.setUserId(REQUESTER);
		request.setCommunityId(COMMUNITY);
		request.setCode("synthetic-code");
		request.setMessage("Synthetic message");
		return request;
	}

	/** A WARN is persisted through ErrorLogs, which needs a database; keep it out of a unit test. */
	private static MockedStatic<ErrorLogs> quietErrorLogs() {
		MockedStatic<ErrorLogs> errors = mockStatic(ErrorLogs.class);
		errors.when(() -> ErrorLogs.add(any(String.class), any(StarLevel.class))).thenReturn(Optional.of(1));
		return errors;
	}

	/** The leaders and community name a join request reads, its template, and quiet ErrorLogs. */
	private static final class JoinRequestStatics implements AutoCloseable {
		private final MockedStatic<Spaces> spaces = mockStatic(Spaces.class);
		private final MockedStatic<FileUtils> files = mockStatic(FileUtils.class);
		private final MockedStatic<ErrorLogs> errors = quietErrorLogs();

		private JoinRequestStatics(List<User> leaders) {
			spaces.when(() -> Spaces.getLeaders(anyInt())).thenReturn(leaders);
			spaces.when(() -> Spaces.getName(anyInt())).thenReturn("Synthetic Community");
			files.when(() -> FileUtils.readFileToString(any(File.class), eq(StandardCharsets.UTF_8)))
					.thenReturn("$$NEWUSER$$ ($$EMAIL$$) asks to join $$COMMUNITY$$: $$APPROVE$$ $$DECLINE$$");
		}

		@Override
		public void close() {
			errors.close();
			files.close();
			spaces.close();
		}
	}

	/** Captures what Mail logs, without passing it on to the root appenders. */
	private static final class LogCapture implements AutoCloseable {
		private final Logger logger = (Logger) LoggerFactory.getLogger(Mail.class);
		private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
		private final boolean additive = logger.isAdditive();

		private LogCapture() {
			appender.start();
			logger.setAdditive(false);
			logger.addAppender(appender);
		}

		private List<String> at(Level level) {
			return appender.list.stream().filter(e -> e.getLevel() == level)
					.map(ILoggingEvent::getFormattedMessage).collect(Collectors.toList());
		}

		/** Exactly one event at {@code level} contains every fragment. */
		private void assertOne(Level level, String... fragments) {
			List<String> matching = at(level).stream()
					.filter(message -> Arrays.stream(fragments).allMatch(message::contains))
					.collect(Collectors.toList());
			assertEquals("one " + level + " event containing " + Arrays.toString(fragments) + " among "
					+ appender.list.stream().map(e -> e.getLevel() + " " + e.getFormattedMessage())
					.collect(Collectors.toList()), 1, matching.size());
		}

		private void assertNoneAtOrAbove(Level level, String fragment) {
			assertFalse(appender.list.stream().anyMatch(
					e -> e.getLevel().isGreaterOrEqual(level) && e.getFormattedMessage().contains(fragment)));
		}

		private void assertNoSyntheticAddress() {
			for (ILoggingEvent event : appender.list) {
				for (String address : SYNTHETIC) {
					assertFalse("an address leaked into the log: " + event.getFormattedMessage(),
							event.getFormattedMessage().contains(address));
				}
			}
		}

		@Override
		public void close() {
			logger.detachAppender(appender);
			logger.setAdditive(additive);
			appender.stop();
		}
	}
}
