package org.starexec.data.to;

/**
 * Interface for receiving progress updates during space/benchmark traversal operations.
 * This follows the Observer pattern to decouple progress reporting from core domain logic.
 * 
 * Implementations can choose how to handle progress updates - e.g., logging,
 * updating a database, sending notifications, etc.
 */
public interface TraversalProgressListener {
    
    /**
     * Called when a new subspace is created during traversal.
     *
     * @param spaceId The ID of the newly created space
     * @param spacePath The path/name of the space
     */
    void onSpaceCreated(int spaceId, String spacePath);
    
    /**
     * Called when a batch of benchmarks has been processed and inserted into the database.
     *
     * @param processedCount The number of benchmarks in this batch
     * @param totalProcessed The total number of benchmarks processed so far
     */
    void onBenchmarksProcessed(int processedCount, int totalProcessed);
    
    /**
     * Called periodically to report overall progress.
     *
     * @param directoriesVisited Number of directories visited so far
     * @param filesFound Total number of valid files found in the archive
     * @param filesProcessed Total number of files processed (inserted into DB)
     * @param spacesCreated Total number of spaces created
     */
    void onProgress(int directoriesVisited, int filesFound, int filesProcessed, int spacesCreated);
    
    /**
     * Called when traversal is complete.
     *
     * @param totalFilesFound Total number of valid files found
     * @param totalFilesProcessed Total number of files processed
     * @param totalSpacesCreated Total number of spaces created
     */
    void onComplete(int totalFilesFound, int totalFilesProcessed, int totalSpacesCreated);

    /**
     * Called when an error occurs during traversal that should be reported to the user.
     * 
     * @param errorMessage The description of the error
     */
    void onError(String errorMessage);
}
