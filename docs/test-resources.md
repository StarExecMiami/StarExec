# StarExec Test Resources

This document outlines the test resources available in StarExec's Maven structure.

## Structure

```text
starexec-app/src/test/resources/
├── org/starexec/test/resources/
│   ├── CVC4Config.txt            # Default configuration text loaded by ResourceLoader
│   ├── postproc.sh               # Legacy SMT post-processor script source
│   ├── jobXmls/
│   │   ├── basicJobXML.xml       # Single-job XML template
│   │   └── jobXML.xml            # Pipeline-job XML template
│   └── fixtures-src/             # Plain-text, project-authored sources for generated ZIPs
├── upload-test/                  # Resources for testing data uploads
│   ├── dependencies/
│   ├── testDataCommands.txt
│   └── uploadTestData.sh
└── test-pipeline/                # Resources for pipeline testing
    ├── README.MD
    ├── DummySolver.c
    ├── bench.txt
    ├── test.xml
    └── build-test-pipeline.sh
```

## Legacy harness classpath resources and generated ZIP fixtures (#141)

Tracked binary `.zip` files remain excluded by `.gitignore` (`**/*.zip`).
Instead, project-authored plain-text sources live under
`starexec-app/src/test/resources/org/starexec/test/resources/fixtures-src/`
(and `postproc.sh`), and `starexec-app/pom.xml` invokes
`scripts/scientific/generate_test_sequence_fixtures.py` during Maven's
`generate-test-resources` phase to emit deterministic ZIPs into
`starexec-app/target/test-classes/org/starexec/test/resources/`:

| Generated archive | Source origin | License / provenance |
|---|---|---|
| `benchproc.zip` | `fixtures-src/benchproc/process` | Project-authored synthetic benchmark processor (MIT); emits `starexec-valid=true` and `test-attribute=test` |
| `postproc.zip` | `postproc.sh`, `fixtures-src/postproc/mybin/hello.txt` | Project-authored SMT post-processor (MIT) |
| `benchmarks.zip` | `fixtures-src/benchmarks/test/{sat,unsat,unknown}.smt2`, `starexec_description.txt` | Project-authored minimal SMT-LIB 2 benchmarks (MIT); replaces historical external `.smt2` files |
| `CVC4.zip` | `fixtures-src/CVC4/bin/starexec_run_default`, `starexec_description.txt` | Project-authored synthetic SMT-LIB 2 solver wrapper providing `default` config (MIT); replaces the unlicensed 2013 binary |
| `smallsolver.zip` | `fixtures-src/smallsolver/bin/starexec_run_default` | Project-authored minimal wrapper for `StressTest` (MIT) |

`app12.zip` (referenced only by `StressTest.java`) has no verified license and
is intentionally outside the scope of `#141`.

## Provenance and deterministic verification

Fixture member hashes and deterministic archive SHA-256 digests are pinned in
[`scripts/scientific/test-sequence-fixtures.lock.json`](../scripts/scientific/test-sequence-fixtures.lock.json)
(`schemaVersion: 1`). Verify sources, generated archives, and classpath setup from
a clean checkout:

```bash
python3 scripts/scientific/verify_fixtures.py
mvn -B -ntp test -pl starexec-app -Dtest=LegacyFixtureClasspathTest
python3 scripts/scientific/verify_fixtures.py --generated-dir starexec-app/target/test-classes/org/starexec/test/resources
python3 -m unittest discover -s scripts/tests -p 'test_*.py' -v
```

`LegacyFixtureClasspathTest` checks that all text templates and all five
generated ZIP archives exist on the classpath with expected entries and
executable bits, and that `ResourceLoader` raises a clear error naming the missing
fixture and expected classpath path when absent.

## Upload Test Data

Upload a standard set of resources (solvers, benchmarks, processors) to a StarExec instance.

Usage:

- Direct Script:  
  `cd starexec-app/src/test/resources/upload-test && ./uploadTestData.sh`

## Test Pipeline

Test the pipeline functionality using provided resources.

Usage:

- Direct Script:

  ```bash
  cd starexec-app/src/test/resources/test-pipeline
  ./build-test-pipeline.sh compile    # Compile the solver
  ./build-test-pipeline.sh package    # Package resources
  ./build-test-pipeline.sh clean      # Clean generated files
  ```

Components:

- **DummySolver**: A simple solver appending "Starexec4ever!" to file output.
- **bench.txt**: Benchmark file.
- **test.xml**: Configuration for a 3-stage pipeline job.
