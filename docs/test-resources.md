# StarExec Test Resources

This document outlines the test resources available in StarExec's Maven structure.

## Structure

```bash
src/test/resources/
├── upload-test/           # Resources for testing data uploads
│   ├── testDataCommands.txt
│   ├── uploadTestData.sh
│   ├── benchmarks/
│   ├── solvers/
│   ├── post-procs/
│   └── bench-procs/
└── test-pipeline/         # Resources for pipeline testing
    ├── README.md
    ├── DummySolver.c
    ├── bench.txt
    ├── test.xml
    └── build-test-pipeline.sh
```

## Upload Test Data

Upload a standard set of resources (solvers, benchmarks, processors) to a StarExec instance.

Usage:

- Maven:  
  `mvn clean package -P upload-test-data`
- Direct Script:  
  `cd src/test/resources/upload-test && ./uploadTestData.sh`

## Test Pipeline

Test the pipeline functionality using provided resources.

Usage:

- Maven:  
  `mvn clean package -P build-test-pipeline`
- Direct Script:

  ```bash
  cd src/test/resources/test-pipeline
  ./build-test-pipeline.sh compile    # Compile the solver
  ./build-test-pipeline.sh package    # Package resources
  ./build-test-pipeline.sh clean      # Clean generated files
  ```

Components:

- **DummySolver**: A simple solver appending "Starexec4ever!" to file output.
- **bench.txt**: Benchmark file.
- **test.xml**: Configuration for a 3-stage pipeline job.

Workflow:

1. Package the resources.
2. Upload the resulting solver and benchmark archives to StarExec.
3. Update solver and benchmark IDs in test.xml.
4. Submit the job.

## Enhancements

- Maven profiles for test data and pipeline packaging.
- Automated builds with dependency management.
- Robust scripting and error handling.
- Standardized Maven structure and clear documentation.

## Quick Commands

```bash
mvn clean package                    # Build the entire project
mvn clean package -P upload-test-data  # Upload test data to StarExec
mvn clean package -P build-test-pipeline # Prepare test-pipeline resources
```

## Migration

Resources were migrated from legacy directories (upload-test/ and test_pipeline/) to adhere to Maven conventions.
