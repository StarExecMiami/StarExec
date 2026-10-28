package org.starexec.data.database;

import org.junit.Test;
import org.mockito.Mockito;
import org.starexec.data.to.Solver;

import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A solver copy that fails must be reported, not passed on as if it were a solver id.
 *
 * <p>copySolver returns -1 for a solver it could not copy, whatever the reason: a database error,
 * a disk error, or a name the directory check refuses. In the same package as the class under
 * test because both methods exercised here are protected.
 */
public class FailedSolverCopyReportingTest {

	/** copySolvers must surface the failure rather than dropping or hiding it. */
	@Test
	public void copySolversSurfacesAFailedCopy() throws Exception {
		Solver refused = new Solver();
		refused.setName("..");
		refused.setPath(Files.createTempDirectory("legacy-solver").toString());

		List<Integer> ids = Solvers.copySolvers(List.of(refused), 5, 1);

		assertEquals(1, ids.size());
		assertTrue("a failed copy must be reported as a non-positive id: " + ids, ids.get(0) <= 0);
	}

	/**
	 * The list-of-spaces association runs in one transaction, so an id the database would refuse
	 * must never reach it: the foreign key failure rolls back the whole batch, undoing the
	 * associations of the solvers that copied successfully.
	 */
	@Test
	public void aFailedCopysIdIsNeverAssociated() throws Exception {
		Connection con = Mockito.mock(Connection.class);
		PreparedStatement ps = Mockito.mock(PreparedStatement.class);
		Mockito.when(con.prepareStatement(Mockito.anyString())).thenReturn(ps);

		Solvers.associate(con, List.of(7, -1, 9), 3);

		Mockito.verify(ps, Mockito.never()).setInt(2, -1);
		Mockito.verify(ps).setInt(2, 7);
		Mockito.verify(ps).setInt(2, 9);
		Mockito.verify(ps, Mockito.times(2)).execute();
	}
}
