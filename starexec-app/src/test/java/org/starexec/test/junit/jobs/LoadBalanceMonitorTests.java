package org.starexec.test.junit.jobs;


import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.starexec.jobs.LoadBalanceMonitor;
import org.starexec.constants.R;

import java.util.HashMap;
import java.util.Map;

public class LoadBalanceMonitorTests {
	private LoadBalanceMonitor monitor = null;

	@BeforeEach
	public void setup() {
		monitor = new LoadBalanceMonitor();
	}

	@Test
	public void testAddSingleUser() {
		Map<Integer, Long> users = new HashMap<>();
		users.put(1, 0L);
		monitor.setUsers(users);
		Assertions.assertEquals(0, (long)monitor.getLoad(1));
		Assertions.assertEquals(0, (long)monitor.getMin());
	}

	@Test
	public void addExistingUser() {
		Map<Integer, Long> users = new HashMap<>();
		users.put(1, 0L);
		monitor.setUsers(users);
		monitor.changeLoad(1, 1);
		monitor.setUsers(users);
		Assertions.assertEquals(1, (long)monitor.getLoad(1));
	}

	@Test
	public void testRemoveSingleUser() {
		Map<Integer, Long> users = new HashMap<>();
		users.put(1, 0L);
		monitor.setUsers(users);
		monitor.changeLoad(1, 12);
		monitor.setUsers(new HashMap<>());

		// user is still there, just inactive
		Assertions.assertEquals(12, (long)monitor.getLoad(1));
		Assertions.assertNull(monitor.getMin());
	}

	@Test
	public void testRemoveMinUser() {
		Map<Integer, Long> users = new HashMap<>();
		users.put(1, 0L);
		users.put(2, 0L);
		monitor.setUsers(users);
		monitor.changeLoad(1, 3);
		Assertions.assertEquals(0, (long)monitor.getMin());
		users.remove(2);
		monitor.setUsers(users);

		Assertions.assertEquals(3, (long)monitor.getMin());
	}

	@Test
	public void testIncrementLoadSingleUser() {
		Map<Integer, Long> users = new HashMap<>();
		users.put(1, 0L);
		monitor.setUsers(users);
		monitor.changeLoad(1, 6);
		Assertions.assertEquals(6, (long)monitor.getLoad(1));
		Assertions.assertEquals(6, (long)monitor.getMin());

		monitor.changeLoad(1, 7);
		Assertions.assertEquals(13, (long)monitor.getLoad(1));
		Assertions.assertEquals(13, (long)monitor.getMin());
	}

	@Test
	public void testIncrementLoadTwoUsers() {
		Map<Integer, Long> users = new HashMap<>();
		users.put(1, 0L);
		users.put(2, 0L);
		monitor.setUsers(users);
		monitor.changeLoad(1, 6);
		Assertions.assertEquals(6, (long)monitor.getLoad(1));
		Assertions.assertEquals(0, (long)monitor.getLoad(2));
		Assertions.assertEquals(0, (long)monitor.getMin());

		monitor.changeLoad(2, 7);
		Assertions.assertEquals(6, (long)monitor.getLoad(1));
		Assertions.assertEquals(7, (long)monitor.getLoad(2));
		Assertions.assertEquals(6, (long)monitor.getMin());
	}

	@Test
	public void testSetUsersEmptyMonitor() {
		Map<Integer, Long> users = new HashMap<>();
		users.put(1, 3L);
		users.put(2, 3L);
		users.put(3, 3L);

		monitor.setUsers(users);

		for (Integer i : users.keySet()) {
			Assertions.assertEquals(3, (long)monitor.getLoad(i));
		}
		Assertions.assertEquals(3, (long)monitor.getMin());
	}

	@Test
	public void testSetUsersNoOverlap() {
		Map<Integer, Long> users = new HashMap<>();
		users.put(4, 0L);
		monitor.setUsers(users);
		monitor.changeLoad(4, 5);
		users = new HashMap<>();
		users.put(1, 6L);
		users.put(2, 6L);
		users.put(3, 6L);
		monitor.setUsers(users);
		for (Integer i : users.keySet()) {
			Assertions.assertEquals(6, (long)monitor.getLoad(i));
		}
		Assertions.assertEquals(5, (long)monitor.getLoad(4));
		Assertions.assertEquals(6, (long)monitor.getMin());
	}

	@Test
	public void testSetUsersAllOverlap() {
		Map<Integer, Long> users = new HashMap<>();
		users.put(1, 0L);
		users.put(2, 0L);
		monitor.setUsers(users);
		monitor.changeLoad(1, 2);
		monitor.changeLoad(2, 2);
		users = new HashMap<>();
		users.put(1, 3L);
		users.put(2, 3L);
		monitor.setUsers(users);
		for (Integer i : users.keySet()) {
			Assertions.assertEquals(2, (long)monitor.getLoad(i));
		}
		Assertions.assertEquals(2, (long)monitor.getMin());
	}

	@Test
	public void skipUserTestSingleUser() {
		Map<Integer, Long> users = new HashMap<>();
		users.put(1, 0L);
		monitor.setUsers(users);
		Assertions.assertFalse(monitor.skipUser(1));
		monitor.changeLoad(1, 3000);
		Assertions.assertFalse(monitor.skipUser(1));
	}

	@Test
	public void skipUserTestTwoUsers() {
		Map<Integer, Long> users = new HashMap<>();
		users.put(1, 0L);
		users.put(2, 0L);
		monitor.setUsers(users);
		monitor.changeLoad(1, 1);
		Assertions.assertFalse(monitor.skipUser(1));
		Assertions.assertFalse(monitor.skipUser(2));
		monitor.changeLoad(1, R.LOAD_DIFFERENCE_THRESHOLD + 10);
		Assertions.assertTrue(monitor.skipUser(1));
		Assertions.assertFalse(monitor.skipUser(2));
	}

	@Test
	public void testAddUserAfterLoad() {
		Map<Integer, Long> users = new HashMap<>();
		users.put(1, 0L);
		monitor.setUsers(users);
		monitor.changeLoad(1, 5);
		users.put(2, 0L);
		monitor.setUsers(users);
		Assertions.assertEquals(5, (long)monitor.getLoad(1));
		Assertions.assertEquals(5, (long)monitor.getLoad(2));
		Assertions.assertEquals(5, (long)monitor.getMin());
	}

	@Test
	public void testForMinWithInactiveUser() {
		Map<Integer, Long> users = new HashMap<>();
		users.put(1, 0L);
		monitor.setUsers(users);
		users.put(2, 0L);
		users.remove(1);
		monitor.setUsers(users);
		monitor.changeLoad(2, 10);
		Assertions.assertEquals(10, (long)monitor.getMin());
	}

	@Test
	public void testAddUserWithInactiveUser() {
		Map<Integer, Long> userOne = new HashMap<>();
		final int userOneId = 1;
		// 24 hours in seconds
		final Long twentyFourHours = 86400L;
		userOne.put(userOneId, twentyFourHours);
		monitor.setUsers(userOne);

		// Make user 1 inactive.
		monitor.setUsers(new HashMap<>());

		Map<Integer, Long> userTwo = new HashMap<>();
		final int userTwoId = 2;
		final Long noLoad = 0L;
		userTwo.put(userTwoId, noLoad);
		monitor.setUsers(userTwo);

		Map<Integer, Long> userOneAndTwo = new HashMap<>();
		userOneAndTwo.put(userOneId, noLoad);
		userOneAndTwo.put(userTwoId, noLoad);

		monitor.setUsers(userOneAndTwo);

		Long userOneLoad = monitor.getLoad(userOneId);
		userOneLoad = userOneLoad == null ? 0L : userOneLoad;

		Long userTwoLoad = monitor.getLoad(userTwoId);
		userTwoLoad = userTwoLoad == null ? 0L : userTwoLoad;

		Assertions.assertEquals(noLoad, monitor.getMin());
		Assertions.assertEquals(twentyFourHours, userOneLoad);
		Assertions.assertEquals(noLoad, userTwoLoad);
	}
}
