package org.starexec.jobs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.starexec.constants.R;
import org.starexec.data.to.Job;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class JobManagerAndClearCacheTest {

	@Test
	public void selectHighPriorityJobIncrementsBalanceInMinimumSelectionBranch() throws Exception {
		Class<?> stateClass = Class.forName("org.starexec.jobs.JobManager$SchedulingState");
		Constructor<?> ctor = stateClass.getDeclaredConstructor(Job.class, String.class, Iterator.class);
		ctor.setAccessible(true);

		Job jobA = new Job();
		jobA.setId(10);
		jobA.setHighPriority();
		Job jobB = new Job();
		jobB.setId(20);
		jobB.setHighPriority();

		Object stateA = ctor.newInstance(jobA, "", List.of().iterator());
		Object stateB = ctor.newInstance(jobB, "", List.of().iterator());

		Map<Integer, Integer> balance = new HashMap<>();
		balance.put(10, 0);
		balance.put(20, 1);

		Method select = JobManager.class.getDeclaredMethod(
				"selectHighPriorityJob", int.class, List.class, Map.class);
		select.setAccessible(true);

		Object chosen = select.invoke(null, 5, List.of(stateA, stateB), balance);
		Field jobField = stateClass.getDeclaredField("job");
		jobField.setAccessible(true);
		assertEquals(10, ((Job) jobField.get(chosen)).getId());
		assertEquals(1, balance.get(10), "Selected minimum-count high-priority job must increment its balance");
	}

	@Test
	public void missingTemplatesFailFastWithoutNullPointer(@TempDir Path tempDir) throws Exception {
		String priorConfig = R.CONFIG_PATH;
		Field mainTemplateField = JobManager.class.getDeclaredField("mainTemplate");
		mainTemplateField.setAccessible(true);
		Object priorMainTemplate = mainTemplateField.get(null);

		Field scriptTemplateField = ClearCacheManager.class.getDeclaredField("scriptTemplate");
		scriptTemplateField.setAccessible(true);
		Object priorScriptTemplate = scriptTemplateField.get(null);

		try {
			R.CONFIG_PATH = tempDir.toString();
			mainTemplateField.set(null, null);
			scriptTemplateField.set(null, null);

			assertThrows(IllegalStateException.class, JobManager::initMainTemplateIf);
			assertThrows(IOException.class, ClearCacheManager::clearSolverCacheOnAllNodes);
		} finally {
			R.CONFIG_PATH = priorConfig;
			mainTemplateField.set(null, priorMainTemplate);
			scriptTemplateField.set(null, priorScriptTemplate);
		}
	}
}
