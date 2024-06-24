import com.i27academy.builds.Docker
import com.i27academy.k8s.K8s

library ('com.i27academy.slb')

def call(Map pipelineParams) {
    Docker docker = new Docker(this)
    K8s k8s = new K8s(this)
    pipeline {
        agent any
        parameters {
            choice(
                name: 'buildOnly',
                choices: 'no\nyes',
                description: 'This will only build the application'
            )
            choice(
                name: 'scanOnly',
                choices: 'no\nyes',
                description: 'This will Scan the application'
            )
            choice(
                name: 'dockerPush',
                choices: 'no\nyes',
                description: 'This will build the app, docker build, docker push'
            )
            choice(
                name: 'deployToDev',
                choices: 'no\nyes',
                description: 'This will Deploy the app to Dev env'
            )
            choice(
                name: 'deployToTest',
                choices: 'no\nyes',
                description: 'This will Deploy the app to Test env'
            )
            choice(
                name: 'deployToStage',
                choices: 'no\nyes',
                description: 'This will Deploy the app to Stage env'
            )
            choice(
                name: 'deployToProd',
                choices: 'no\nyes',
                description: 'This will Deploy the app to Prod env'
            )
        }
        environment {
            APPLICATION_NAME = "${pipelineParams.appName}"
            POM_VERSION = readMavenPom().getVersion()
            POM_PACKAGING = readMavenPom().getPackaging()
            DOCKER_HUB = "docker.io/sureshindrala"
            DOCKER_CREDS = credentials('i27devopsb2_docker_creds')
            SONAR_URL = "http://34.66.190.70:9000/"
            SONAR_TOKEN = credentials('sonar_creds')
            // Uncomment and define the following if needed
            // GKE_DEV_CLUSTER_NAME = "cart-cluster"
            // GKE_DEV_ZONE = "us-west1-a"
            // GKE_DEV_PROJECT = "delta-sprite-416312"
            // GKE_TST_CLUSTER_NAME = "tst-cluster"
            // GKE_TST_ZONE = "us-west1-b"
            // GKE_TST_PROJECT = "nice-carving-4118012"
            // DOCKER_IMAGE_TAG = sh(script: 'git log -1 --pretty=%h', returnStdout: true).trim()
            // K8S_DEV_FILE = "k8s_dev.yaml"
            // K8S_TST_FILE = "k8s_tst.yaml"
            // K8S_STAGE_FILE = "k8s_stg.yaml"
            // K8S_PROD_FILE = "k8s_prd.yaml"
            // DEV_NAMESPACE = "cart-dev-ns"
            // TEST_NAMESPACE = "cart-tst-ns"
        }
        tools {
            maven 'Maven-3.8.8'
            jdk 'JDK-17'
        }
        stages {
            stage ('Authenticate to Google Cloud GKE') {
                steps {
                    echo "Executing in Google Cloud auth stage"
                    script {
                        k8s.auth_login("${env.GKE_DEV_CLUSTER_NAME}", "${env.GKE_DEV_ZONE}", "${env.GKE_DEV_PROJECT}")
                    }
                }
            }
            stage ('Build') {
                when {
                    anyOf {
                        expression { params.buildOnly == 'yes' }
                    }
                }
                steps {
                    script {
                        echo "********** Executing Addition Method **********"
                        println docker.add(4, 5)
                        docker.buildApp("${env.APPLICATION_NAME}")
                    }
                }
            }
            stage ('Unit Tests') {
                when {
                    anyOf {
                        expression {
                            params.buildOnly == 'yes' || params.dockerPush == 'yes'
                        }
                    }
                }
                steps {
                    echo "Performing Unit tests for ${env.APPLICATION_NAME} application"
                    sh "mvn test"
                }
                post {
                    always {
                        junit 'target/surefire-reports/*.xml'
                    }
                }
            }
            stage ('Sonar') {
                when {
                    expression { params.scanOnly == 'yes' }
                }
                steps {
                    echo "Starting Sonarqube With Quality Gates"
                    withSonarQubeEnv('SonarQube') {
                        sh """
                            mvn clean verify sonar:sonar \
                                -Dsonar.projectKey=i27-eureka \
                                -Dsonar.host.url=${env.SONAR_URL} \
                                -Dsonar.login=${SONAR_TOKEN}
                        """
                    }
                    timeout(time: 2, unit: 'MINUTES') {
                        script {
                            waitForQualityGate abortPipeline: true
                        }
                    }
                }
            }
            stage ('Docker Build and Push') {
                when {
                    anyOf {
                        expression { params.dockerPush == 'yes' }
                    }
                }
                steps {
                    script {
                        dockerBuildandPush().call()
                    }
                }
            }
            stage ('Deploy to Dev') {
                when {
                    expression { params.deployToDev == 'yes' }
                }
                steps {
                    script {
                        imageValidation().call()
                        def docker_image = "${env.DOCKER_HUB}/${env.APPLICATION_NAME}:${env.DOCKER_IMAGE_TAG}"
                        k8s.auth_login("${env.GKE_DEV_CLUSTER_NAME}", "${env.GKE_DEV_ZONE}", "${env.GKE_DEV_PROJECT}")
                        k8s.k8sdeploy("${env.K8S_DEV_FILE}", docker_image, "${env.DEV_NAMESPACE}")
                        echo "Deployed to Dev Successfully!!!!"
                    }
                }
            }
            stage ('Deploy to Test') {
                when {
                    expression { params.deployToTest == 'yes' }
                }
                steps {
                    script {
                        imageValidation().call()
                        def docker_image = "${env.DOCKER_HUB}/${env.APPLICATION_NAME}:${env.DOCKER_IMAGE_TAG}"
                        k8s.auth_login("${env.GKE_DEV_CLUSTER_NAME}", "${env.GKE_DEV_ZONE}", "${env.GKE_DEV_PROJECT}")
                        k8s.k8sdeploy("${env.K8S_TST_FILE}", docker_image)
                        echo "Deployed to Test Successfully!!!!"
                    }
                }
            }
            stage ('Deploy to Stage') {
                when {
                    expression { params.deployToStage == 'yes' }
                }
                steps {
                    script {
                        imageValidation().call()
                        dockerDeploy('stage', '7761', '8761').call()
                    }
                }
            }
            stage ('Deploy to Prod') {
                when {
                    allOf {
                        expression { params.deployToProd == 'yes' }
                        branch 'release/*'
                    }
                }
                steps {
                    timeout(time: 300, unit: 'SECONDS') {
                        input message: "Deploying ${env.APPLICATION_NAME} to prod ????", ok: 'yes', submitter: 'greesh'
                    }
                    script {
                        imageValidation().call()
                        dockerDeploy('prod', '8761', '8761').call()
                    }
                }
            }
            stage ('clean') {
                steps {
                    cleanWs()
                }
            }
        }
    }
}

// This Jenkinsfile is for the Eureka Deployment.

// This method will build image and push to registry
def dockerBuildandPush() {
    return {
        echo "******************************** Build Docker Image ********************************"
        sh "cp ${workspace}/target/i27-${env.APPLICATION_NAME}-${env.POM_VERSION}.${env.POM_PACKAGING} ./.cicd"
        sh "ls -la ./.cicd"
        sh "docker build --force-rm --no-cache --pull --rm=true --build-arg JAR_SOURCE=i27-${env.APPLICATION_NAME}-${env.POM_VERSION}.${env.POM_PACKAGING} -t ${env.DOCKER_HUB}/${env.APPLICATION_NAME}:${env.DOCKER_IMAGE_TAG} ./.cicd"
        echo "******************************** Login to Docker Repo ********************************"
        sh "docker login -u ${DOCKER_CREDS_USR} -p ${DOCKER_CREDS_PSW}"
        echo "******************************** Docker Push ********************************"
        sh "docker push ${env.DOCKER_HUB}/${env.APPLICATION_NAME}:${env.DOCKER_IMAGE_TAG}"
        echo "Pushed the image successfully!!!"
    }
}

// This method is developed for Deploying our App in different environments
def dockerDeploy(envDeploy, hostPort, contPort) {
    return {
        echo "******************************** Deploying to $envDeploy Environment ********************************"
        withCredentials([usernamePassword(credentialsId: 'maha_docker_vm_creds', passwordVariable: 'PASSWORD', usernameVariable: 'USERNAME')]) {
            script {
                sh "sshpass -p ${PASSWORD} -v ssh -o StrictHostKeyChecking=no ${USERNAME}@${docker_server_ip} docker pull ${env.DOCKER_HUB}/${env.APPLICATION_NAME}:${GIT_COMMIT}"
                try {
                    echo "Stopping the Container"
                    sh "sshpass -p ${PASSWORD} -v ssh -o StrictHostKeyChecking=no ${USERNAME}@${docker_server_ip} docker stop ${env.APPLICATION_NAME}-$envDeploy"
                    echo "Removing the Container"
                    sh "sshpass -p ${PASSWORD} -v ssh -o StrictHostKeyChecking=no ${USERNAME}@${docker_server_ip} docker rm ${env.APPLICATION_NAME}-$envDeploy"
                } catch (err) {
                    echo "Caught the Error: $err"
                }
                echo "Creating the Container"
                sh "sshpass -p ${PASSWORD} -v ssh -o StrictHostKeyChecking=no ${USERNAME}@${docker_server_ip} docker run -d -p $hostPort:$contPort --name ${env.APPLICATION_NAME}-$envDeploy ${env.DOCKER_HUB}/${env.APPLICATION_NAME}:${GIT_COMMIT}"
            }
        }
    }
}

def imageValidation() {
    Docker docker = new Docker(this)
    K8s k8s = new K8s(this)
    return {
        println("Pulling the docker image")
        try {
            sh "docker pull ${env.DOCKER_HUB}/${env.APPLICATION_NAME}:${env.DOCKER_IMAGE_TAG}"
        } catch (Exception e) {
            println("OOPS!, docker images with this tag are not available")
            println("LINE BEFORE ENTERING DOCKER METHOD")
            docker.buildApp("${env.APPLICATION_NAME}")
            dockerBuildandPush().call()
        }
    }
}



