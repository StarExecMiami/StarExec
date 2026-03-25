package org.starexec.test.junit.util.matrixView;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.data.database.Jobs;
import org.starexec.data.database.Permissions;
import org.starexec.data.to.Job;
import org.starexec.data.security.ValidatorStatusCode;
import org.starexec.exceptions.StarExecException;
import org.starexec.util.matrixView.MatrixViewUtil;

import javax.servlet.http.HttpServletResponse;

import static org.junit.Assert.fail;

public class MatrixViewUtilTests {

    @Test
    public void getJobIfAvailableToUserThrowsWhenBaseJobLookupReturnsNull() throws Exception {
        HttpServletResponse response = Mockito.mock(HttpServletResponse.class);

        try (MockedStatic<Permissions> permissionsMock = Mockito.mockStatic(Permissions.class);
             MockedStatic<Jobs> jobsMock = Mockito.mockStatic(Jobs.class)) {
            permissionsMock.when(() -> Permissions.canUserSeeJob(10, 20))
                .thenReturn(new ValidatorStatusCode(true, ""));
            jobsMock.when(() -> Jobs.get(10)).thenReturn(null);

            try {
                MatrixViewUtil.getJobIfAvailableToUser(10, 20, response);
                fail("Expected StarExecException when Jobs.get returns null");
            } catch (StarExecException expected) {
                // expected
            }
        }
    }

    @Test
    public void getJobIfAvailableToUserThrowsWhenMatrixJobLookupReturnsNull() throws Exception {
        HttpServletResponse response = Mockito.mock(HttpServletResponse.class);

        Job baseJob = new Job();
        baseJob.setPrimarySpace(5);

        try (MockedStatic<Permissions> permissionsMock = Mockito.mockStatic(Permissions.class);
             MockedStatic<Jobs> jobsMock = Mockito.mockStatic(Jobs.class)) {
            permissionsMock.when(() -> Permissions.canUserSeeJob(10, 20))
                .thenReturn(new ValidatorStatusCode(true, ""));
            jobsMock.when(() -> Jobs.get(10)).thenReturn(baseJob);
            jobsMock.when(() -> Jobs.getJobForMatrix(10)).thenReturn(null);

            try {
                MatrixViewUtil.getJobIfAvailableToUser(10, 20, response);
                fail("Expected StarExecException when Jobs.getJobForMatrix returns null");
            } catch (StarExecException expected) {
                // expected
            }
        }
    }
}
