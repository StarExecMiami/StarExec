package org.starexec.test.integration;

import org.starexec.test.resources.IResourceLoader;
import org.starexec.test.resources.ResourceLoader;

/**
 * Test-scoped implementation of ResourceLoaderFactory.
 *
 * This class lives in src/test/java and creates ResourceLoader instances.
 * It's loaded via reflection by TestSequence, so there's no compile-time
 * dependency from production code on test classes.
 */
public class ResourceLoaderFactoryImpl implements ResourceLoaderFactory {
    @Override
    public IResourceLoader createResourceLoader() {
        try {
            return new ResourceLoader();
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to instantiate ResourceLoader. " +
                            "Ensure test dependencies (Selenium, etc.) are available.",
                    e);
        }
    }

    @Override
    public boolean isAvailable() {
        try {
            // Try to load the ResourceLoader class to verify test dependencies exist
            Class.forName("org.starexec.test.resources.ResourceLoader");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
