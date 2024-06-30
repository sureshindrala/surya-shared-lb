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
        echo "****** Nodes List ***********"
        kubectl get nodes
        
        """
    }
    def k8sdeploy(fileName,docker_image) {
        jenkins.sh """#!/bin/bash
        echo "Excuting K8S Deploy method"
        echo "Final Image Tag is $docker_image"
        sed -i "s|DIT|$docker_image|g" ./.cicd/$fileName
        kubectl apply -f ./.cicd/$fileName
        """

    }

    def k8sHelmChartDeploy(appName, env, helmChartPath, imageTag) {
       jenkins.sh """#!/bin/bash
       echo "*************** Helm Groovy method Starts here ***************"
       echo "Checking if helm chart exists"
       if helm list | grep -q "${appName}-${env}-chart"; then
        echo "Chart Exists !!!!!!!!!"
        echo "Upgrading the Chart !!!!!!"
        helm upgrade ${appName}-${env}-chart -f ./.cicd/k8s/values_${env}.yaml --set image.tag=${imageTag} ${helmChartPath}
       else 
        echo "Installing the Chart"
        helm install ${appName}-${env}-chart -f ./.cicd/k8s/values_${env}.yaml --set image.tag=${imageTag} ${helmChartPath}
       fi
       # helm install chartname -f valuesfilepath chartpath
       # helm upgrade chartname -f valuefilepath chartpath
       """     
    }

    
    def gitClone() {
       jenkins.sh """#!/bin/bash
       echo "*************** Entering Git Clone Method ***************"
       git clone -b main https://github.com/sureshindrala/surya-shared-lb.git
       echo "Listing the files"
       ls -la 
       echo "Showing the files under i27-shared-lib repo"
       ls -la surya-shared-lb
       echo "Showing the files under chart folder"
       ls -la surya-shared-lb/chart/
       # echo "Showing the link in default folder"
       # ls -la surya-shared-lb/src/com/i27academy/k8s/default/
       """ 
    }    
        
}