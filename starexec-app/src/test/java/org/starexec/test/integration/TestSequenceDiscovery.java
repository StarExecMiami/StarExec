package org.starexec.test.integration;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.platform.commons.support.ReflectionSupport;

/** Discovers and validates the legacy {@link TestSequence} suite. */
public final class TestSequenceDiscovery {
	static final String BASE_PACKAGE = "org.starexec.test.integration";

	private TestSequenceDiscovery() {
	}

	/**
	 * Finds every concrete top-level sequence on the test classpath. Test fixtures
	 * use nested classes, so excluding member classes keeps them out of the live
	 * inventory without maintaining a class-name exclusion list.
	 */
	public static List<DiscoveredSequence> discover() {
		List<Class<?>> discovered = ReflectionSupport.findAllClassesInPackage(
				BASE_PACKAGE,
				TestSequenceDiscovery::isConcreteTopLevelSequence,
				className -> true);
		List<Class<? extends TestSequence>> sequenceClasses = new ArrayList<>();
		for (Class<?> candidate : discovered) {
			sequenceClasses.add(candidate.asSubclass(TestSequence.class));
		}
		return inspectClasses(sequenceClasses);
	}

	static List<DiscoveredSequence> inspectClasses(
			Collection<Class<? extends TestSequence>> sequenceClasses) {
		List<Class<? extends TestSequence>> orderedClasses = new ArrayList<>(sequenceClasses);
		orderedClasses.sort(Comparator.comparing(Class::getName));

		Set<String> classNames = new HashSet<>();
		List<DiscoveredSequence> sequences = new ArrayList<>();
		for (Class<? extends TestSequence> sequenceClass : orderedClasses) {
			validateSequenceClass(sequenceClass);
			if (!classNames.add(sequenceClass.getName())) {
				throw new IllegalStateException("Duplicate legacy sequence class: " + sequenceClass.getName());
			}

			List<Method> methods = new ArrayList<>();
			for (Method method : sequenceClass.getDeclaredMethods()) {
				if (method.isAnnotationPresent(StarexecTest.class)) {
					methods.add(method);
				}
			}
			methods.sort(Comparator.comparing(Method::getName).thenComparing(Method::toGenericString));
			validateMethods(sequenceClass, methods);
			sequences.add(new DiscoveredSequence(sequenceClass, methods));
		}

		if (sequences.isEmpty()) {
			throw new IllegalStateException(
					"Legacy TestSequence discovery found zero concrete classes under " + BASE_PACKAGE);
		}
		return List.copyOf(sequences);
	}

	public static int countMethods(Collection<DiscoveredSequence> sequences) {
		return sequences.stream().mapToInt(sequence -> sequence.testMethods().size()).sum();
	}

	static TestSequence instantiate(DiscoveredSequence sequence) {
		return ReflectionSupport.newInstance(sequence.sequenceClass());
	}

	public static String identity(Class<? extends TestSequence> sequenceClass, Method method) {
		return sequenceClass.getName() + "#" + method.getName();
	}

	private static boolean isConcreteTopLevelSequence(Class<?> candidate) {
		return candidate != TestSequence.class
				&& TestSequence.class.isAssignableFrom(candidate)
				&& !candidate.isInterface()
				&& !Modifier.isAbstract(candidate.getModifiers())
				&& candidate.getEnclosingClass() == null;
	}

	private static void validateSequenceClass(Class<? extends TestSequence> sequenceClass) {
		if (sequenceClass == TestSequence.class
				|| sequenceClass.isInterface()
				|| Modifier.isAbstract(sequenceClass.getModifiers())) {
			throw new IllegalStateException("Legacy sequence must be concrete: " + sequenceClass.getName());
		}
		try {
			sequenceClass.getDeclaredConstructor();
		} catch (NoSuchMethodException e) {
			throw new IllegalStateException(
					"Legacy sequence requires a no-argument constructor: " + sequenceClass.getName(), e);
		}
	}

	private static void validateMethods(Class<? extends TestSequence> sequenceClass, List<Method> methods) {
		if (methods.isEmpty()) {
			throw new IllegalStateException(
					"Legacy sequence declares zero @StarexecTest methods: " + sequenceClass.getName());
		}

		Set<String> methodNames = new HashSet<>();
		for (Method method : methods) {
			String identity = identity(sequenceClass, method);
			if (!methodNames.add(method.getName())) {
				throw new IllegalStateException("Legacy test identity is duplicate: " + identity);
			}
			if (method.getParameterCount() != 0) {
				throw new IllegalStateException("Legacy test must accept no arguments: " + identity);
			}
			if (method.getReturnType() != Void.TYPE) {
				throw new IllegalStateException("Legacy test must return void: " + identity);
			}
			if (Modifier.isStatic(method.getModifiers())) {
				throw new IllegalStateException("Legacy test must be an instance method: " + identity);
			}
		}
	}

	public record DiscoveredSequence(
			Class<? extends TestSequence> sequenceClass,
			List<Method> testMethods) {
		public DiscoveredSequence {
			testMethods = List.copyOf(testMethods);
		}
	}
}
