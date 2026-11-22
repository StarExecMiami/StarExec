package org.starexec.data.security;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.constants.R;
import org.starexec.data.database.Communities;
import org.starexec.data.database.Jobs;
import org.starexec.data.database.Permissions;
import org.starexec.data.database.Processors;
import org.starexec.data.database.Queues;
import org.starexec.data.database.Settings;
import org.starexec.data.database.Solvers;
import org.starexec.data.database.Spaces;
import org.starexec.data.database.Users;
import org.starexec.data.to.Configuration;
import org.starexec.data.to.Job;
import org.starexec.data.to.Permission;
import org.starexec.data.to.Space;
import org.starexec.jobs.LoadBalanceMonitor;
import org.starexec.servlets.CreateJob;
import org.starexec.servlets.DoJobPage;
import org.starexec.servlets.SaveConfiguration;
import org.starexec.util.SessionUtil;
import org.starexec.util.Validator;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class NullAndValidationGuardsTest {

	@BeforeAll
	public static void initValidator() {
		Validator.initialize();
	}

	@Test
	public void solverSecurityHandlesNullSolverWithoutNpe() {
		Configuration cfg = new Configuration();
		cfg.setId(5);
		cfg.setName("default");

		try (MockedStatic<Solvers> solvers = Mockito.mockStatic(Solvers.class);
			 MockedStatic<GeneralSecurity> general = Mockito.mockStatic(GeneralSecurity.class)) {
			solvers.when(() -> Solvers.get(99)).thenReturn(null);
			solvers.when(() -> Solvers.getConfiguration(5)).thenReturn(cfg);
			solvers.when(() -> Solvers.getSolverByConfig(5, false)).thenReturn(null);
			general.when(() -> GeneralSecurity.hasAdminReadPrivileges(1)).thenReturn(false);

			assertFalse(SolverSecurity.canUserDownloadSolver(99, 1).isSuccess());
			assertFalse(SolverSecurity.canUserUpdateConfiguration(5, 1, "renamed", "desc").isSuccess());
		}
	}

	@Test
	public void processorAndJobSecurityHandleMissingEntitiesWithoutNpe() {
		try (MockedStatic<Processors> procs = Mockito.mockStatic(Processors.class);
			 MockedStatic<GeneralSecurity> general = Mockito.mockStatic(GeneralSecurity.class);
			 MockedStatic<Jobs> jobs = Mockito.mockStatic(Jobs.class);
			 MockedStatic<Queues> queues = Mockito.mockStatic(Queues.class);
			 MockedStatic<Permissions> perms = Mockito.mockStatic(Permissions.class);
			 MockedStatic<Settings> settings = Mockito.mockStatic(Settings.class)) {
			general.when(() -> GeneralSecurity.hasAdminWritePrivileges(1)).thenReturn(false);
			procs.when(() -> Processors.get(44)).thenReturn(null);

			assertFalse(ProcessorSecurity.doesUserOwnProcessor(44, 1).isSuccess());
			assertFalse(ProcessorSecurity.canUserEditProcessor(44, 1, "validName", "desc").isSuccess());
			assertFalse(ProcessorSecurity.doesUserOwnProcessors(null, 1).isSuccess());

			Job ownerJob = new Job();
			ownerJob.setId(10);
			ownerJob.setUserId(1);
			jobs.when(() -> Jobs.get(10)).thenReturn(ownerJob);
			jobs.when(() -> Jobs.isReadOnly(10)).thenReturn(false);
			queues.when(() -> Queues.get(2)).thenReturn(new org.starexec.data.to.Queue());
			queues.when(() -> Queues.getUserQueues(1)).thenReturn(null);
			assertFalse(JobSecurity.canChangeQueue(10, 1, 2).isSuccess());

			perms.when(() -> Permissions.get(1, 3)).thenReturn(new Permission(true));
			settings.when(() -> Settings.getProfileById(7)).thenReturn(null);
			assertFalse(JobSecurity.canCreateQuickJobWithCommunityDefaults(1, 3, 7).isSuccess());
		}
	}

	@Test
	public void spaceSecurityHandlesNullSpacePermissionAndSettings() {
		try (MockedStatic<Spaces> spaces = Mockito.mockStatic(Spaces.class);
			 MockedStatic<Permissions> perms = Mockito.mockStatic(Permissions.class);
			 MockedStatic<Users> users = Mockito.mockStatic(Users.class);
			 MockedStatic<GeneralSecurity> general = Mockito.mockStatic(GeneralSecurity.class);
			 MockedStatic<Communities> comms = Mockito.mockStatic(Communities.class)) {
			general.when(() -> GeneralSecurity.hasAdminWritePrivileges(1)).thenReturn(true);
			comms.when(() -> Communities.isCommunity(8)).thenReturn(false);
			spaces.when(() -> Spaces.get(8)).thenReturn(null);
			assertFalse(SpaceSecurity.canUpdateProperties(8, 1, "newName", false).isSuccess());

			assertFalse(SpaceSecurity.canUpdateSettings(8, null, "val", 1).isSuccess());

			general.when(() -> GeneralSecurity.hasAdminWritePrivileges(1)).thenReturn(false);
			perms.when(() -> Permissions.get(1, 8)).thenReturn(new Permission(true));
			assertFalse(SpaceSecurity.canUpdateSettings(8, "name", "newName", 1).isSuccess());

			users.when(() -> Users.get(1)).thenReturn(new org.starexec.data.to.User());
			spaces.when(() -> Spaces.getDetails(8, 1)).thenReturn(new Space());
			perms.when(() -> Permissions.get(1, 8)).thenReturn(null);
			assertFalse(SpaceSecurity.canUserLinkAllOrphaned(1, 1, 8).isSuccess());

			assertFalse(SpaceSecurity.canUserRemoveSpace(1, null).isSuccess());
		}
	}

	@Test
	public void queuesAndLoadBalanceMonitorAndServletsHandleNullsSafely(@TempDir Path tempDir) throws Exception {
		try (MockedStatic<Queues> queues = Mockito.mockStatic(Queues.class)) {
			queues.when(Queues::developerJobsExist).thenCallRealMethod();
			queues.when(Queues::getAllActive).thenReturn(null);
			assertFalse(Queues.developerJobsExist());
		}

		LoadBalanceMonitor monitor = new LoadBalanceMonitor();
		assertFalse(monitor.skipUser(999));
		monitor.setUsers(java.util.Collections.singletonMap(1, 100L));
		assertFalse(monitor.skipUser(999));

		HttpServletRequest saveReq = mock(HttpServletRequest.class);
		HttpServletResponse saveResp = mock(HttpServletResponse.class);
		when(saveReq.getParameter("solverId")).thenReturn("123");
		when(saveReq.getParameter("saveConfigName")).thenReturn("validCfg");
		when(saveReq.getParameter("saveConfigDesc")).thenReturn("desc");
		when(saveReq.getParameter("saveConfigContents")).thenReturn("echo hi");
		try (MockedStatic<UploadSecurity> uploadSec = Mockito.mockStatic(UploadSecurity.class);
			 MockedStatic<SessionUtil> session = Mockito.mockStatic(SessionUtil.class);
			 MockedStatic<Solvers> solvers = Mockito.mockStatic(Solvers.class)) {
			uploadSec.when(UploadSecurity::uploadsFrozen).thenReturn(false);
			session.when(() -> SessionUtil.getUserId(saveReq)).thenReturn(1);
			solvers.when(() -> Solvers.get(123)).thenReturn(null);
			Method doPost = SaveConfiguration.class.getDeclaredMethod(
					"doPost", HttpServletRequest.class, HttpServletResponse.class);
			doPost.setAccessible(true);
			doPost.invoke(new SaveConfiguration(), saveReq, saveResp);
			verify(saveResp).sendError(HttpServletResponse.SC_BAD_REQUEST, "The selected solver could not be found");
		}

		Method getPairOutput = DoJobPage.class.getDeclaredMethod("getPairOutput", File.class, int.class);
		getPairOutput.setAccessible(true);
		try (MockedStatic<org.starexec.data.database.JobPairs> jobPairs =
					 Mockito.mockStatic(org.starexec.data.database.JobPairs.class)) {
			jobPairs.when(() -> org.starexec.data.database.JobPairs.getPairDetailed(55)).thenReturn(null);
			assertDoesNotThrow(() -> getPairOutput.invoke(null, tempDir.toFile(), 55));
		}

		HttpServletRequest jobReq = mock(HttpServletRequest.class);
		when(jobReq.getParameter("sid")).thenReturn("5");
		when(jobReq.getParameter(R.BENCHMARKING_FRAMEWORK_OPTION)).thenReturn("RUNSOLVER");
		when(jobReq.getParameter("cpuTimeout")).thenReturn("10");
		when(jobReq.getParameter("wallclockTimeout")).thenReturn("10");
		when(jobReq.getParameter("maxMem")).thenReturn("1.0");
		when(jobReq.getParameter("seed")).thenReturn("0");
		when(jobReq.getParameter("queue")).thenReturn("1");
		when(jobReq.getParameter("runChoice")).thenReturn("choose");
		when(jobReq.getParameter("benchChoice")).thenReturn(null);
		when(jobReq.getParameterValues(R.BENCHMARK)).thenReturn(new String[]{"11"});
		when(jobReq.getParameterValues("configs")).thenReturn(new String[]{"99"});
		try (MockedStatic<SessionUtil> session = Mockito.mockStatic(SessionUtil.class);
			 MockedStatic<Users> users = Mockito.mockStatic(Users.class);
			 MockedStatic<Permissions> perms = Mockito.mockStatic(Permissions.class);
			 MockedStatic<Solvers> solvers = Mockito.mockStatic(Solvers.class)) {
			session.when(() -> SessionUtil.getUserId(jobReq)).thenReturn(1);
			users.when(() -> Users.isDiskQuotaExceeded(1)).thenReturn(false);
			perms.when(() -> Permissions.get(1, 5)).thenReturn(new Permission(true));
			perms.when(() -> Permissions.canUserSeeBenchs(List.of(11), 1)).thenReturn(true);
			solvers.when(() -> Solvers.getSolverByConfig(99, false)).thenReturn(null);

			Method isValid = CreateJob.class.getDeclaredMethod("isValid", HttpServletRequest.class);
			isValid.setAccessible(true);
			ValidatorStatusCode code = (ValidatorStatusCode) isValid.invoke(new CreateJob(), jobReq);
			assertFalse(code.isSuccess());
			org.junit.jupiter.api.Assertions.assertEquals(
					"One or more selected configurations could not be found",
					code.getMessage()
			);
		}
	}
}
