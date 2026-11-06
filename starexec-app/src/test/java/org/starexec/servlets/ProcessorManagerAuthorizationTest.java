package org.starexec.servlets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.mockito.MockedStatic;
import org.starexec.data.database.Processors;
import org.starexec.data.to.Permission;
import org.starexec.util.PartWrapper;
import org.starexec.util.SessionUtil;
import org.starexec.util.Util;
import org.starexec.util.Validator;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.util.HashMap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProcessorManagerAuthorizationTest {
    @BeforeAll
    static void initializeValidator() {
        Validator.initialize();
    }

    private void assertDenied(Permission permission, String type) throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        PartWrapper upload = mock(PartWrapper.class);
        when(request.getMethod()).thenReturn("POST");
        when(request.getContentType()).thenReturn("multipart/form-data; boundary=synthetic-fixture");
        when(upload.getName()).thenReturn("processor.tar");
        HashMap<String, Object> form = new HashMap<>();
        form.put("action", "add");
        form.put("name", "authorization-fixture");
        form.put("desc", "Synthetic processor authorization fixture");
        form.put("com", "2");
        form.put("type", type);
        form.put("uploadMethod", "local");
        form.put("file", upload);
        try (MockedStatic<Util> util = mockStatic(Util.class);
             MockedStatic<SessionUtil> session = mockStatic(SessionUtil.class);
             MockedStatic<Processors> processors = mockStatic(Processors.class)) {
            util.when(() -> Util.parseMultipartRequest(request)).thenReturn(form);
            session.when(() -> SessionUtil.getPermission(request, 2)).thenReturn(permission);
            new ProcessorManager().doPost(request, response);
            session.verify(() -> SessionUtil.getPermission(request, 2));
            verify(response).sendError(HttpServletResponse.SC_FORBIDDEN,
                    "Only community leaders can add types to their communities");
            verify(upload, never()).write(any(File.class));
            processors.verifyNoInteractions();
        }
    }

    @Test
    void missingMembershipCannotUploadBenchmarkProcessor() throws Exception {
        assertDenied(null, "bench");
    }

    @Test
    void missingMembershipCannotUploadPostprocessor() throws Exception {
        assertDenied(null, "post");
    }

    @Test
    void nonleaderCannotUploadBenchmarkProcessor() throws Exception {
        assertDenied(new Permission(), "bench");
    }

    @Test
    void nonleaderCannotUploadPostprocessor() throws Exception {
        assertDenied(new Permission(), "post");
    }
}
