#!/bin/bash

# Script to manage StarExec test-pipeline resources
# Usage: ./build-test-pipeline.sh [compile|package|clean|help]

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../../.." && pwd)"
PIPELINE_DIR="$SCRIPT_DIR"

usage() {
    echo "Usage: $0 [command]"
    echo ""
    echo "Available commands:"
    echo "  compile  - Compiles DummySolver.c"
    echo "  package  - Creates .tar files for uploading to StarExec"
    echo "  clean    - Cleans up generated files"
    echo "  help     - Shows this help"
    echo ""
    echo "Example: $0 package"
}

compile_solver() {
    echo "Compiling DummySolver.c..."
    cd "$PIPELINE_DIR"
    
    if ! command -v gcc &> /dev/null; then
        echo "Error: gcc is not installed"
        exit 1
    fi
    
    gcc -o dummySolver DummySolver.c
    echo "✓ DummySolver compiled successfully"
}

package_resources() {
    echo "Packaging resources for StarExec..."
    cd "$PIPELINE_DIR"
    
    # Compile if executable does not exist
    if [[ ! -f "dummySolver" ]]; then
        compile_solver
    fi
    
    # Create solver tarball
    tar -cf dummySolver.tar dummySolver
    echo "✓ dummySolver.tar created"
    
    # Create benchmark tarball
    tar -cf benchmark.tar bench.txt
    echo "✓ benchmark.tar created"
    
    echo ""
    echo "Resources ready to upload to StarExec:"
    echo "  - dummySolver.tar (solver)"
    echo "  - benchmark.tar (benchmark)"
    echo "  - test.xml (job configuration)"
}

clean_generated() {
    echo "Cleaning up generated files..."
    cd "$PIPELINE_DIR"
    
    rm -f dummySolver
    rm -f dummySolver.tar
    rm -f benchmark.tar
    
    echo "✓ Generated files removed"
}

main() {
    case "${1:-help}" in
        compile)
            compile_solver
            ;;
        package)
            package_resources
            ;;
        clean)
            clean_generated
            ;;
        help|--help|-h)
            usage
            ;;
        *)
            echo "Error: Unknown command '$1'"
            echo ""
            usage
            exit 1
            ;;
    esac
}

main "$@"