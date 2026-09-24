package org.starexec.test.junit.backend;

import org.junit.Test;
import org.starexec.backend.Backend;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

/** Contract tests for the conservative default termination result. */
public class BackendKillConfirmationTests {

    @Test
    public void acceptedKillRequestDoesNotProveExecutionAbsence() {
        assertEquals(Backend.KillOutcome.UNPROVEN, killPairConfirmedFor(true));
    }

    @Test
    public void rejectedKillRequestDoesNotProveExecutionAbsence() {
        assertEquals(Backend.KillOutcome.UNPROVEN, killPairConfirmedFor(false));
    }

    private Backend.KillOutcome killPairConfirmedFor(boolean killPairResult) {
        AtomicInteger killRequests = new AtomicInteger();
        InvocationHandler handler = (proxy, method, arguments) -> {
            if (method.getName().equals("killPair")) {
                killRequests.incrementAndGet();
                return killPairResult;
            }
            if (method.isDefault()) {
                return InvocationHandler.invokeDefault(proxy, method, arguments);
            }
            throw new AssertionError("Unexpected backend method: " + method.getName());
        };
        Backend backend = (Backend) Proxy.newProxyInstance(
            Backend.class.getClassLoader(),
            new Class<?>[] {Backend.class},
            handler
        );
        Backend.KillOutcome outcome = backend.killPairConfirmed(1);
        assertEquals(1, killRequests.get());
        return outcome;
    }
}
