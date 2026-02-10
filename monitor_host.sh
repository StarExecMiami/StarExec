#!/bin/bash
echo "Starting host monitoring (vmstat). Logging to host_metrics.csv"
vmstat 1 > host_metrics.csv