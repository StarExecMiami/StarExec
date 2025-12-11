package org.starexec.test.integration;

import org.starexec.test.resources.IResourceLoader;

public interface ResourceLoaderFactory {
    IResourceLoader createResourceLoader();

    boolean isAvailable();
}
