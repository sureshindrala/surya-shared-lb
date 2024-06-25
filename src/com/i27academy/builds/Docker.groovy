package com.i27academy.builds

class Docker {
    def jenkins
    Docker(jenkins) {
        this.jenkins = jenkins
    }

    // Addition method
    def add(firstNumber, secondNumber) { //add(1,2)
        // logic
        return firstNumber+secondNumber
    }

    // Application Build
    def buildApp(appName) {
        jenkins.sh """#!/bin/bash
        echo "Building the Shared Library $appName Application"
        mvn clean package -DskipTests=true
        """
    }
    
