#!/bin/bash

# Script para gestionar los recursos de test-pipeline de StarExec
# Uso: ./build-test-pipeline.sh [compile|package|clean|help]

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../../.." && pwd)"
PIPELINE_DIR="$SCRIPT_DIR"

usage() {
    echo "Uso: $0 [comando]"
    echo ""
    echo "Comandos disponibles:"
    echo "  compile  - Compila el DummySolver.c"
    echo "  package  - Crea los archivos .tar para subir a StarExec"
    echo "  clean    - Limpia archivos generados"
    echo "  help     - Muestra esta ayuda"
    echo ""
    echo "Ejemplo: $0 package"
}

compile_solver() {
    echo "Compilando DummySolver.c..."
    cd "$PIPELINE_DIR"
    
    if ! command -v gcc &> /dev/null; then
        echo "Error: gcc no está instalado"
        exit 1
    fi
    
    gcc -o dummySolver DummySolver.c
    echo "✓ DummySolver compilado exitosamente"
}

package_resources() {
    echo "Empaquetando recursos para StarExec..."
    cd "$PIPELINE_DIR"
    
    # Compilar si no existe el ejecutable
    if [[ ! -f "dummySolver" ]]; then
        compile_solver
    fi
    
    # Crear archivo tar del solver
    tar -cf dummySolver.tar dummySolver
    echo "✓ dummySolver.tar creado"
    
    # Crear archivo tar del benchmark
    tar -cf benchmark.tar bench.txt
    echo "✓ benchmark.tar creado"
    
    echo ""
    echo "Recursos listos para subir a StarExec:"
    echo "  - dummySolver.tar (solver)"
    echo "  - benchmark.tar (benchmark)"
    echo "  - test.xml (configuración de job)"
}

clean_generated() {
    echo "Limpiando archivos generados..."
    cd "$PIPELINE_DIR"
    
    rm -f dummySolver
    rm -f dummySolver.tar
    rm -f benchmark.tar
    
    echo "✓ Archivos generados eliminados"
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
            echo "Error: Comando desconocido '$1'"
            echo ""
            usage
            exit 1
            ;;
    esac
}

main "$@"