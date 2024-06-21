package com.i27academy.k8s

class k8s {
    def jenkins 
    k8(jenkins) {
        this.jenkins = jenkins

    }
    def auth_login(gke_cluster_name, gke_zone, gke_project) {
        jenkins.sh """#!/bin/bash
        echo "Entering Authentication method for GKE cluster login"
        #gcloud config set account jenkins@chromatic-craft-424811-h4.iam.gserviceaccount.com
        gcloud compute instances list
        echo "******** Listing number of nodes in K8S *********"
        kubectl get nodes

        """
    }
}