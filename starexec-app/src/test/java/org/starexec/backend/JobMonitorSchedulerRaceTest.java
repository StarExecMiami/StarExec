package org.starexec.backend;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class JobMonitorSchedulerRaceTest {

	@Test
	public void localMonitorWakeupWhilePollRunsDoesNotSpawnDuplicateChains() throws Exception {
		LocalJobMonitor monitor = new LocalJobMonitor();
		ScheduledThreadPoolExecutor spyScheduler = Mockito.spy(new ScheduledThreadPoolExecutor(1));
		Field schedulerField = LocalJobMonitor.class.getDeclaredField("scheduler");
		schedulerField.setAccessible(true);
		((java.util.concurrent.ScheduledExecutorService) schedulerField.get(monitor)).shutdownNow();
		schedulerField.set(monitor, spyScheduler);

		try {
			monitor.start();
			Field genField = LocalJobMonitor.class.getDeclaredField("pollGeneration");
			genField.setAccessible(true);
			long gen1 = (long) genField.get(monitor);

			// Simulate poll 1 actively running while a wakeup arrives concurrently.
			Method cancel = LocalJobMonitor.class.getDeclaredMethod("cancelAndRescheduleNow");
			cancel.setAccessible(true);
			cancel.invoke(monitor);
			cancel.invoke(monitor);

			long genAfterWakeups = (long) genField.get(monitor);
			assertEquals(gen1 + 2, genAfterWakeups);

			// Stale poll 1 finishing must NOT schedule a second poll; only one active future exists in queue.
			Method pollMethod = LocalJobMonitor.class.getDeclaredMethod("pollAndReschedule", long.class);
			pollMethod.setAccessible(true);
			pollMethod.invoke(monitor, gen1);

			long activePolls = spyScheduler.getQueue().stream()
					.filter(r -> r instanceof java.util.concurrent.ScheduledFuture<?> f
							&& !f.isCancelled() && !f.isDone())
					.count();
			assertEquals(1L, activePolls,
					"Wakeups + stale poll completion must maintain exactly one active polling chain");
		} finally {
			monitor.stop();
		}
	}

	@Test
	public void containerMonitorWakeupDuringActivePollKeepsSingleChain() throws Exception {
		PodmanBackend backend = mock(PodmanBackend.class);
		CountDownLatch inPoll = new CountDownLatch(1);
		CountDownLatch releasePoll = new CountDownLatch(1);
		AtomicInteger pollCount = new AtomicInteger(0);
		when(backend.getCompletedContainers()).thenAnswer(inv -> {
			if (pollCount.incrementAndGet() == 1) {
				inPoll.countDown();
				releasePoll.await(5, TimeUnit.SECONDS);
			}
			return List.of();
		});

		ContainerJobMonitor monitor = new ContainerJobMonitor(backend);
		Field schedulerField = ContainerJobMonitor.class.getDeclaredField("scheduler");
		schedulerField.setAccessible(true);
		ScheduledThreadPoolExecutor spyScheduler = Mockito.spy(new ScheduledThreadPoolExecutor(1));
		((java.util.concurrent.ScheduledExecutorService) schedulerField.get(monitor)).shutdownNow();
		schedulerField.set(monitor, spyScheduler);

		try {
			Field genField = ContainerJobMonitor.class.getDeclaredField("pollGeneration");
			genField.setAccessible(true);
			Field runningField = ContainerJobMonitor.class.getDeclaredField("running");
			runningField.setAccessible(true);
			runningField.set(monitor, true);
			genField.set(monitor, 1L);

			Method pollMethod = ContainerJobMonitor.class.getDeclaredMethod("pollAndReschedule", long.class);
			pollMethod.setAccessible(true);
			Thread activePoll = new Thread(() -> {
				try {
					pollMethod.invoke(monitor, 1L);
				} catch (Exception ignored) {
				}
			});
			activePoll.start();
			assertTrue(inPoll.await(5, TimeUnit.SECONDS));

			monitor.notifyNewWorkSubmitted();
			monitor.notifyNewWorkSubmitted();
			releasePoll.countDown();
			activePoll.join(5000);

			long activePolls = spyScheduler.getQueue().stream()
					.filter(r -> r instanceof java.util.concurrent.ScheduledFuture<?> f
							&& !f.isCancelled() && !f.isDone())
					.count();
			assertEquals(1L, activePolls,
					"Expected exactly one active poll chain after concurrent wakeups, found: " + activePolls);
		} finally {
			monitor.stop();
		}
	}
}
