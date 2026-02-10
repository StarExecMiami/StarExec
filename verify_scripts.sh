#!/bin/bash

# Mock script to test getProcessorScript function from functions.bash

# Paste the function here for testing (since sourcing the whole file requires too many dependencies)
function getProcessorScript {
	if [ -d "./process" ] && [ -f "./process/process" ]; then
		echo "./process/process"
	elif [ -f "./process" ]; then
		echo "./process"
	elif [ -f "./run.sh" ]; then
		echo "./run.sh"
	elif [ -f "./starexec_run" ]; then
		echo "./starexec_run"
	elif [ -f "./run" ]; then
		echo "./run"
	else
		echo ""
	fi
}

function test_script {
    local name=$1
    local type=$2 # file or dir
    
    echo "Testing with $name ($type)..."
    
    # Setup
    mkdir -p test_env
    cd test_env
    
    if [ "$type" == "file" ]; then
        touch "$name"
        chmod +x "$name"
    elif [ "$type" == "dir" ]; then
        mkdir -p "$name"
        touch "$name/$name" # handles process/process case
        chmod +x "$name/$name"
    fi
    
    result=$(getProcessorScript)
    
    if [ -n "$result" ]; then
        echo "Found: $result"
    else
        echo "NOT FOUND"
    fi
    
    # Cleanup
    cd ..
    rm -rf test_env
}

echo "Starting tests..."

test_script "process" "file"
test_script "process" "dir"
test_script "run.sh" "file"
test_script "starexec_run" "file"
test_script "run" "file"
test_script "nonexistent" "file"

echo "Tests completed."
