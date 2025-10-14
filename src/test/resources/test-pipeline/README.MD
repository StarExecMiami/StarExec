# Test Pipeline

## Description

This directory contains resources for testing the functionality of StarExec pipelines. A pipeline consists of multiple stages where the output of one stage becomes the input of the next.

## Author

Alex Guo (University of Iowa)

## Contents

- **`DummySolver.c`** - Source code of the test solver
- **`dummySolver`** - Compiled executable of the solver (Linux x86_64)  
- **`dummySolver.tar`** - Solver packaged for uploading to StarExec
- **`bench.txt`** - Simple benchmark for testing
- **`benchmark.tar`** - Benchmark packaged for uploading to StarExec
- **`test.xml`** - Job XML configuration with a 3-stage pipeline

## DummySolver Functionality

The solver reads an input file and:

1. Prints the content of the file
2. Adds the string "Starexec4ever!"

In a 3-stage pipeline, this produces a cumulative output where each stage adds more time prefixes.

## Expected Output

### Stage 1

```
0.00/0.01	Starexec4ever!
0.00/0.01	Starexec4ever! 
0.00/0.01	EOF
```

### Stage 2

```
0.00/0.01	0.00/0.01	Starexec4ever!
0.00/0.01	0.00/0.01	Starexec4ever! 
0.00/0.01	0.00/0.01	EOF
0.00/0.01	
0.00/0.01	Starexec4ever! 
0.00/0.01	EOF
```

### Stage 3

```
0.00/0.00	0.00/0.01	0.00/0.01	Starexec4ever!
0.00/0.00	0.00/0.01	0.00/0.01	Starexec4ever! 
0.00/0.00	0.00/0.01	0.00/0.01	EOF
0.00/0.00	0.00/0.01	
0.00/0.00	0.00/0.01	Starexec4ever! 
0.00/0.00	0.00/0.01	EOF
0.00/0.00	
0.00/0.00	Starexec4ever! 
0.00/0.01	EOF
```

## Usage

1. Upload the solver (`dummySolver.tar`) to StarExec
2. Upload the benchmark (`benchmark.tar`) to StarExec  
3. Modify `test.xml` with the correct configuration and benchmark IDs
4. Submit the job using the XML file

**Note:** The IDs in `test.xml` must be updated with the real values from your StarExec instance.

## Additional Documentation

For more information on using pipelines, see: https://wiki.uiowa.edu/display/stardev/User+Guide

## License

The MIT License (MIT)

Copyright (c) 2012-18 The University of Iowa

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
