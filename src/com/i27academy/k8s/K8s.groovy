package com.i27academy.k8s

class K8s{
    def jenkins
    K8s(jenkins) {
        this.jenkins = jenkins
    }
    def auth_login() {
        jenkins.sh """#!/bin/bash
        echo "***********Entering in The GKE cluster*******"
        gcloud config set account jenkins@chromatic-craft-424811-h4.iam.gserviceaccount.com 
        gcloud compute instances list
        """
    }
}