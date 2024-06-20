package com.i27academy.builds

class Docker {
    def jenkins
    Docker(jenkins) {
        this.jenkins = jenkins
    }
        // Addition Method
    def add(firstNumber,secondNumber) { // add(1,2)
            // logic
        return firstNumber+secondNumber
    }
    // Application Build 
    // Docker Build

    def buildApp() {
        jenkins.sh """#/bin/bash
        echo "Building Eureka application from shared library*"
        "mvn clean package -DskipTests=true"
         """
        
    }

}

