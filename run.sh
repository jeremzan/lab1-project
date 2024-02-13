#!/bin/bash
java WebServer
if [ $? -eq 0 ]; then
    echo "Execution successful."
else
    echo "Execution failed."
    exit 1
fi
