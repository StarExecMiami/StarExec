#!/bin/sh

# This script runs the Maven profile to upload the test data.
# Make sure that the StarexecCommand.jar artifact has been built with 'mvn package'.

mvn -P upload-test-data install
