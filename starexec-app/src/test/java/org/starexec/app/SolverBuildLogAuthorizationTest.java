package org.starexec.app;

import org.junit.jupiter.api.Test;
import org.starexec.data.database.Solvers;
import org.starexec.data.security.SolverSecurity;
import org.starexec.data.security.ValidatorStatusCode;
import org.starexec.util.SessionUtil;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.WebApplicationException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SolverBuildLogAuthorizationTest {
    @Test void deniedBuildLogDoesNotResolveMetadataOrReadFiles() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        try (var session = mockStatic(SessionUtil.class);
             var security = mockStatic(SolverSecurity.class);
             var solvers = mockStatic(Solvers.class)) {
            session.when(() -> SessionUtil.getUserId(request)).thenReturn(7);
            security.when(() -> SolverSecurity.canUserSeeBuildLog(9, 7)).thenReturn(new ValidatorStatusCode(false));
            var refused = assertThrows(WebApplicationException.class, () -> new RESTServices().getSolverBuildLog(9, request));
            assertEquals("not available", refused.getResponse().getEntity());
            solvers.verifyNoInteractions();
        }
    }
}
