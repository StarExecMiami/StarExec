package org.starexec.servlets;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.starexec.data.database.Spaces;
import org.starexec.data.to.Configuration;
import org.starexec.data.to.Job;
import org.starexec.data.to.JobSpace;
import org.starexec.data.to.Solver;
import org.starexec.util.SessionUtil;
import org.starexec.util.matrixView.Matrix;
import org.starexec.util.matrixView.MatrixElement;
import org.starexec.util.matrixView.MatrixViewUtil;

import javax.servlet.RequestDispatcher;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JobMatrixViewControllerTest {

	private static JobSpace makeSpace(int id, int jobId, String name) {
		JobSpace space = new JobSpace();
		space.setId(id);
		space.setJobId(jobId);
		space.setName(name);
		return space;
	}

	@SuppressWarnings("unchecked")
	@Test
	void emptyMatrixWithChildrenSetsSortedChildSpaces() throws Exception {
		HttpServletRequest request = mock(HttpServletRequest.class);
		HttpServletResponse response = mock(HttpServletResponse.class);
		RequestDispatcher dispatcher = mock(RequestDispatcher.class);
		when(request.getParameter("stage")).thenReturn("1");
		when(request.getParameter("jobSpaceId")).thenReturn("126");
		when(request.getRequestDispatcher("/WEB-INF/secure/details/jobMatrixView.jsp"))
				.thenReturn(dispatcher);

		JobSpace parent = makeSpace(126, 10, "root");
		Job job = new Job();
		job.setId(10);
		Matrix emptyMatrix = mock(Matrix.class);
		when(emptyMatrix.getSolverConfigsByColumn()).thenReturn(Collections.emptyList());
		when(emptyMatrix.getInternalMatrixRepresentation()).thenReturn(new ArrayList<>());

		JobSpace childB = makeSpace(127, 10, "beta");
		JobSpace childA = makeSpace(128, 10, "Alpha");

		try (MockedStatic<SessionUtil> session = mockStatic(SessionUtil.class);
			 MockedStatic<Spaces> spaces = mockStatic(Spaces.class);
			 MockedStatic<MatrixViewUtil> matrixUtil = mockStatic(MatrixViewUtil.class);
			 MockedStatic<Matrix> matrixStatic = mockStatic(Matrix.class)) {
			session.when(() -> SessionUtil.getUserId(request)).thenReturn(1);
			spaces.when(() -> Spaces.getJobSpace(126)).thenReturn(parent);
			matrixUtil.when(() -> MatrixViewUtil.getJobIfAvailableToUser(10, 1, response)).thenReturn(job);
			matrixStatic.when(() -> Matrix.getMatrixForJobSpaceFromJobAndStageNumber(job, 126, 1))
					.thenReturn(emptyMatrix);
			spaces.when(() -> Spaces.getSubSpacesForJob(126, false))
					.thenReturn(Arrays.asList(childB, childA));

			new JobMatrixViewController().doGet(request, response);

			ArgumentCaptor<List<JobSpace>> captor = ArgumentCaptor.forClass(List.class);
			verify(request).setAttribute(org.mockito.ArgumentMatchers.eq("childSpaces"), captor.capture());
			List<JobSpace> actual = captor.getValue();
			assertEquals(2, actual.size());
			assertEquals("Alpha", actual.get(0).getName());
			assertEquals("beta", actual.get(1).getName());
			verify(request).setAttribute("isMatrixEmpty", true);
			verify(request).setAttribute("hasChildSpaces", true);
			verify(dispatcher).forward(request, response);
		}
	}

	@Test
	void emptyMatrixWithNoChildrenSetsEmptyChildSpaces() throws Exception {
		HttpServletRequest request = mock(HttpServletRequest.class);
		HttpServletResponse response = mock(HttpServletResponse.class);
		RequestDispatcher dispatcher = mock(RequestDispatcher.class);
		when(request.getParameter("stage")).thenReturn("1");
		when(request.getParameter("jobSpaceId")).thenReturn("126");
		when(request.getRequestDispatcher("/WEB-INF/secure/details/jobMatrixView.jsp"))
				.thenReturn(dispatcher);

		JobSpace parent = makeSpace(126, 10, "root");
		Job job = new Job();
		job.setId(10);
		Matrix emptyMatrix = mock(Matrix.class);
		when(emptyMatrix.getSolverConfigsByColumn()).thenReturn(Collections.emptyList());
		when(emptyMatrix.getInternalMatrixRepresentation()).thenReturn(new ArrayList<>());

		try (MockedStatic<SessionUtil> session = mockStatic(SessionUtil.class);
			 MockedStatic<Spaces> spaces = mockStatic(Spaces.class);
			 MockedStatic<MatrixViewUtil> matrixUtil = mockStatic(MatrixViewUtil.class);
			 MockedStatic<Matrix> matrixStatic = mockStatic(Matrix.class)) {
			session.when(() -> SessionUtil.getUserId(request)).thenReturn(1);
			spaces.when(() -> Spaces.getJobSpace(126)).thenReturn(parent);
			matrixUtil.when(() -> MatrixViewUtil.getJobIfAvailableToUser(10, 1, response)).thenReturn(job);
			matrixStatic.when(() -> Matrix.getMatrixForJobSpaceFromJobAndStageNumber(job, 126, 1))
					.thenReturn(emptyMatrix);
			spaces.when(() -> Spaces.getSubSpacesForJob(126, false)).thenReturn(Collections.emptyList());

			new JobMatrixViewController().doGet(request, response);

			verify(request).setAttribute("isMatrixEmpty", true);
			verify(request).setAttribute("childSpaces", Collections.emptyList());
			verify(request).setAttribute("hasChildSpaces", false);
			verify(dispatcher).forward(request, response);
		}
	}

	@Test
	void nonEmptyMatrixSkipsChildSpaceLookup() throws Exception {
		HttpServletRequest request = mock(HttpServletRequest.class);
		HttpServletResponse response = mock(HttpServletResponse.class);
		RequestDispatcher dispatcher = mock(RequestDispatcher.class);
		when(request.getParameter("stage")).thenReturn("1");
		when(request.getParameter("jobSpaceId")).thenReturn("126");
		when(request.getRequestDispatcher("/WEB-INF/secure/details/jobMatrixView.jsp"))
				.thenReturn(dispatcher);

		JobSpace parent = makeSpace(126, 10, "root");
		Job job = new Job();
		job.setId(10);
		Matrix nonEmptyMatrix = mock(Matrix.class);
		List<Pair<Solver, Configuration>> cols = Collections.singletonList(
				new ImmutablePair<>(new Solver(), new Configuration()));
		ArrayList<ArrayList<MatrixElement>> rows = new ArrayList<>();
		rows.add(new ArrayList<>(Collections.singletonList(mock(MatrixElement.class))));
		when(nonEmptyMatrix.getSolverConfigsByColumn()).thenReturn(cols);
		when(nonEmptyMatrix.getInternalMatrixRepresentation()).thenReturn(rows);

		try (MockedStatic<SessionUtil> session = mockStatic(SessionUtil.class);
			 MockedStatic<Spaces> spaces = mockStatic(Spaces.class);
			 MockedStatic<MatrixViewUtil> matrixUtil = mockStatic(MatrixViewUtil.class);
			 MockedStatic<Matrix> matrixStatic = mockStatic(Matrix.class)) {
			session.when(() -> SessionUtil.getUserId(request)).thenReturn(1);
			spaces.when(() -> Spaces.getJobSpace(126)).thenReturn(parent);
			matrixUtil.when(() -> MatrixViewUtil.getJobIfAvailableToUser(10, 1, response)).thenReturn(job);
			matrixStatic.when(() -> Matrix.getMatrixForJobSpaceFromJobAndStageNumber(job, 126, 1))
					.thenReturn(nonEmptyMatrix);

			new JobMatrixViewController().doGet(request, response);

			spaces.verify(() -> Spaces.getSubSpacesForJob(anyInt(), anyBoolean()), never());
			verify(request).setAttribute("isMatrixEmpty", false);
			verify(request).setAttribute("childSpaces", Collections.emptyList());
			verify(request).setAttribute("hasChildSpaces", false);
			verify(dispatcher).forward(request, response);
		}
	}
}
