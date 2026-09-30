package org.starexec.test.integration;

import org.starexec.logger.StarLevel;
import org.starexec.logger.StarLogger;
import org.starexec.test.TestUtil;
import org.starexec.test.resources.IResourceLoader;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

public abstract class TestSequence {
	interface ExecutionListener {
		ExecutionListener NONE = new ExecutionListener() {
		};

		default void testStarted(Method method) {
		}

		default void testFailure(Method method, Throwable failure) {
		}

		default void testFinished(Method method) {
		}

		default void lifecycleFailure(String phase, Throwable failure) {
		}
	}

	protected static final StarLogger log = StarLogger.getLogger(TestSequence.class);
	protected String sequenceName = "No Name";
	protected final TestStatus status = new TestStatus();
	protected String message = "No Message";
	protected int testsPassed = 0;
	protected int testsFailed = 0;
	protected Throwable error = null;
	protected IResourceLoader loader = null;
	// maps the names of tests to some data about them. Every test gets an entry
	// when the TestSequence object is created
	final LinkedHashMap<String, TestResult> testResults = new LinkedHashMap<>();

	public TestSequence() {
		initTestResults();
		sequenceName = getTestName(); // this method is implemented in every subclass
	}

	private void initTestResults() {
		List<Method> tests = this.getTests();
		for (Method m : tests) {
			TestResult t = new TestResult();
			t.setName(m.getName());
			testResults.put(m.getName(), t);
		}
	}

	/**
	 * Clears the results of every test and sets the status of this sequence to "not
	 * run"
	 */
	protected final void clearResults() {
		error = null;
		testsPassed = 0;
		testsFailed = 0;
		message = "No Message";
		status.setCode(TestStatus.TestStatusCode.STATUS_NOT_RUN.getVal());
		for (TestResult r : testResults.values()) {
			r.clearMessages();
			r.addMessage("test not started");
			r.getStatus().setCode(TestStatus.TestStatusCode.STATUS_NOT_RUN.getVal());
			r.setError(null);
			r.setTime(0);
		}
	}

	/**
	 * Runs this test sequence by calling setup, then running the tests, then
	 * calling teardown. All previous results
	 * are cleared and the status of the TestSequence is updated throughout
	 * 
	 * @return
	 */
	protected final boolean execute() {
		return execute(ExecutionListener.NONE);
	}

	final boolean execute(ExecutionListener listener) {
		clearResults();
		status.setCode(TestStatus.TestStatusCode.STATUS_RUNNING.getVal());
		Throwable setupFailure = null;
		Throwable teardownFailure = null;

		try {
			try {
				loader = initializeResourceLoader();
				turnOffExternalLogging();
				setup();
			} catch (Throwable failure) {
				setupFailure = unwrap(failure);
				recordLifecycleFailure("setup", setupFailure, listener == ExecutionListener.NONE);
				listener.lifecycleFailure("setup", setupFailure);
			}
			if (setupFailure == null) {
				runTests(listener);
			}
		} finally {
			try {
				teardown();
			} catch (Throwable failure) {
				teardownFailure = unwrap(failure);
				recordLifecycleFailure("teardown", teardownFailure, listener == ExecutionListener.NONE);
				listener.lifecycleFailure("teardown", teardownFailure);
			}
		}

		if (setupFailure == null && teardownFailure == null && testsFailed == 0) {
			status.setCode(TestStatus.TestStatusCode.STATUS_SUCCESS.getVal());
			setMessage("test completed successfully");
			return true;
		}
		status.setCode(TestStatus.TestStatusCode.STATUS_FAILED.getVal());
		if (setupFailure == null && teardownFailure == null) {
			setMessage("failed tests detected");
		}
		return false;
	}

	/**
	 * Returns the name of this test sequence
	 * 
	 * @return the name
	 */
	abstract protected String getTestName();

	/**
	 * Turns off logging in some 3rd part libraries used by testing. 3rd party logs
	 * tend to be extremely verbose and drown out our logging
	 */
	private void turnOffExternalLogging() {
		StarLogger.getLogger("java_cup.runtime").setLevel(StarLevel.OFF);
		StarLogger.getLogger("trax").setLevel(StarLevel.OFF);

		StarLogger.getLogger("selenium.webdriver").setLevel(StarLevel.OFF);
		StarLogger.getLogger("org.openqa").setLevel(StarLevel.OFF);
		StarLogger.getLogger("org.eclipse").setLevel(StarLevel.OFF);
		StarLogger.getLogger("org.jboss").setLevel(StarLevel.OFF);
		StarLogger.getLogger("org.apache").setLevel(StarLevel.OFF);
		StarLogger.getLogger("org.hamcrest").setLevel(StarLevel.OFF);
		StarLogger.getLogger("org.cyberneko").setLevel(StarLevel.OFF);
		StarLogger.getLogger("org.testng").setLevel(StarLevel.OFF);

		StarLogger.getLogger("org.w3c").setLevel(StarLevel.OFF);

		StarLogger.getLogger("bsh").setLevel(StarLevel.OFF);
		StarLogger.getLogger("net.sf").setLevel(StarLevel.OFF);

		StarLogger.getLogger("org.apache.http.wire").setLevel(StarLevel.OFF);
		StarLogger.getLogger("org.apache.http.impl.conn.Wire").setLevel(StarLevel.OFF);
		StarLogger.getLogger("org.apache.http.impl.conn.LoggingManagedHttpClientConnection").setLevel(StarLevel.OFF);
		StarLogger.getLogger("org.apache.http.impl.execchain.MainClientExec").setLevel(StarLevel.OFF);

		StarLogger.getLogger("org.apache.http").setLevel(StarLevel.OFF);
		StarLogger.getLogger("selenium.webdriver.remote.remote_connection").setLevel(StarLevel.OFF);

		StarLogger.getLogger("com.google").setLevel(StarLevel.OFF);
		StarLogger.getLogger("com.opera").setLevel(StarLevel.OFF);
		StarLogger.getLogger("com.thoughtworks").setLevel(StarLevel.OFF);
		StarLogger.getLogger("com.steadystate").setLevel(StarLevel.OFF);
		StarLogger.getLogger("com.beust").setLevel(StarLevel.OFF);
		StarLogger.getLogger("com.sun").setLevel(StarLevel.OFF);

		StarLogger.getLogger("com.keypoint").setLevel(StarLevel.OFF);
		StarLogger.getLogger("org.jfree").setLevel(StarLevel.OFF);

		StarLogger.getLogger("netscape.javascript").setLevel(StarLevel.OFF);
		StarLogger.getLogger("net.sourceforge").setLevel(StarLevel.OFF);

		StarLogger.getLogger("com.gargoylesoftware").setLevel(StarLevel.OFF);
	}

	/**
	 * This function is called before the tests belonging to this sequence are run.
	 * All initialization should be done here (creating spaces, solvers, etc. that
	 * are
	 * used by the tests in this sequence).
	 * 
	 * @throws Exception
	 */

	abstract protected void setup() throws Exception;

	protected final void runTests() {
		runTests(ExecutionListener.NONE);
	}

	private void runTests(ExecutionListener listener) {
		List<Method> afters = getAfters();
		for (Method method : getTests()) {
			TestResult result = testResults.get(method.getName());
			result.clearMessages();
			result.addMessage("test running");
			result.getStatus().setCode(TestStatus.TestStatusCode.STATUS_RUNNING.getVal());
			long started = System.currentTimeMillis();
			Throwable failure = null;
			listener.testStarted(method);
			try {
				method.setAccessible(true);
				method.invoke(this, (Object[]) null);
			} catch (Throwable thrown) {
				failure = unwrap(thrown);
			}

			for (Method after : afters) {
				try {
					after.setAccessible(true);
					after.invoke(this, method);
				} catch (Throwable thrown) {
					Throwable afterFailure = unwrap(thrown);
					if (failure == null) {
						failure = afterFailure;
					} else if (failure != afterFailure) {
						failure.addSuppressed(afterFailure);
					}
				}
			}

			result.setTime(System.currentTimeMillis() - started);
			if (failure == null) {
				result.getStatus().setCode(TestStatus.TestStatusCode.STATUS_SUCCESS.getVal());
				result.addMessage("test executed without errors");
				testsPassed++;
			} else {
				result.setError(failure);
				result.addMessage(failureMessage(failure));
				result.getStatus().setCode(TestStatus.TestStatusCode.STATUS_FAILED.getVal());
				testsFailed++;
				if (listener == ExecutionListener.NONE) {
					log.error("legacy test failed: " + getClass().getName() + "#" + method.getName(), failure);
				}
			}

			try {
				if (failure != null) {
					listener.testFailure(method, failure);
				}
			} finally {
				listener.testFinished(method);
			}
		}
	}

	/**
	 * This function is called after all the tests have finished running
	 * It is called from a finally block, including when setup fails.
	 */

	abstract protected void teardown() throws Exception;

	public final String getName() {
		return sequenceName;
	}

	public final TestStatus getStatus() {
		return status;
	}

	public final String getMessage() {
		if (message == null) {
			return "no message";
		}
		return message;
	}

	protected final void setMessage(String newMessage) {
		message = newMessage;
	}

	protected final void setStatusCode(int statusCode) {
		status.setCode(statusCode);
	}

	protected final void setName(String newName) {
		sequenceName = newName;
	}

	/**
	 * Gets the total number of tests in this sequence
	 * 
	 * @return the integer number
	 */
	public final int getTestCount() {
		return getTests().size();
	}

	/**
	 * Gets the number of tests that have passed in this sequence
	 * 
	 * @return the integer number
	 */
	public final int getTestsPassed() {
		return testsPassed;
	}

	/**
	 * Gets the number of tests that have failed in this sequence
	 * 
	 * @return the integer number
	 */

	public final int getTestsFailed() {
		return testsFailed;
	}

	/**
	 * Gets the test results for every test in this sequence
	 * 
	 * @return
	 */
	public final List<TestResult> getTestResults() {
		List<TestResult> tr = new ArrayList<>();
		tr.addAll(testResults.values());
		return tr;
	}

	public final TestResult getTestResult(String testName) {
		return testResults.get(testName);
	}

	/**
	 * Gets the trace for the most recent error that affected this
	 * test sequence (in the setup-- errors in individual tests are reported there)
	 * If there was no error, returns "no error"
	 * 
	 * @return The stack trace as a string
	 */
	public final String getErrorTrace() {
		return TestUtil.getErrorTrace(error);
	}

	/**
	 * Retrieves a list of all methods declared in the current class that have the
	 * 
	 * @StarexecTest annotation
	 * @return
	 */

	protected List<Method> getTests() {
		return getMethodsWithAnnotation(StarexecTest.class);
	}

	/**
	 * Retrieves a list of all methods that have the @StarexecAfter annotation
	 * 
	 * @return
	 */
	protected List<Method> getAfters() {
		return getMethodsWithAnnotation(StarexecAfter.class);
	}

	private List<Method> getMethodsWithAnnotation(Class<? extends Annotation> annotationClass) {
		Method[] methods = this.getClass().getDeclaredMethods();

		List<Method> tests = new ArrayList<>();
		for (Method m : methods) {
			if (hasAnnotation(m, annotationClass)) {
				tests.add(m);
			}
		}
		tests.sort(Comparator.comparing(Method::getName).thenComparing(Method::toGenericString));
		return tests;
	}

	/**
	 * Determines whether the given method is an after test method, based on whether
	 * it has the @StarexecAfter annotation
	 * 
	 * @param m The method to check
	 * @return
	 */
	protected static boolean hasAnnotation(Method m, Class<? extends Annotation> annotationClass) {
		Annotation[] anns = m.getAnnotations();
		for (Annotation a : anns) {
			if (a.annotationType().equals(annotationClass)) {
				return true;
			}
		}
		return false;
	}

	private void recordLifecycleFailure(String phase, Throwable failure, boolean logFailure) {
		status.setCode(TestStatus.TestStatusCode.STATUS_FAILED.getVal());
		setMessage(phase + " failed: " + failureMessage(failure));
		if (logFailure) {
			log.error("legacy sequence " + getClass().getName() + "#" + phase + " failed", failure);
		}
		if (error == null) {
			error = failure;
		} else if (error != failure) {
			error.addSuppressed(failure);
		}
	}

	private static Throwable unwrap(Throwable failure) {
		if (failure instanceof InvocationTargetException && failure.getCause() != null) {
			return failure.getCause();
		}
		return failure;
	}

	private static String failureMessage(Throwable failure) {
		return failure.getMessage() == null ? failure.getClass().getName() : failure.getMessage();
	}

	/**
	 * This appends the given message to the log of an individual test. It uses
	 * reflection to figure out which test called it.
	 * 
	 * @param message
	 */
	protected final void addMessage(String message) {
		try {
			String methodName = Thread.currentThread().getStackTrace()[2].getMethodName();
			this.getTestResult(methodName).addMessage(message);
		} catch (Exception e) {
			log.error("addMessage", e);
		}

	}

	private IResourceLoader initializeResourceLoader() {
		try {
			Class<?> clazz = Class.forName("org.starexec.test.integration.ResourceLoaderFactoryImpl");
			ResourceLoaderFactory factory = (ResourceLoaderFactory) clazz.getDeclaredConstructor().newInstance();
			return factory.createResourceLoader();
		} catch (Exception e) {
			log.warn("Could not instantiate ResourceLoader: " + e.getMessage());
			return null;
		}
	}

}
