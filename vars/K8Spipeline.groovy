import com.i27academy.builds.Docker
import com.i27academy.k8s.K8s

def call(Map pipelineParams) {
    Docker docker = new Docker(this)
    K8s k8s = new K8s(this)
    pipeline {
        agent {
            label 'k8s-slave'
        }
        parameters {
            choice(name: 'buildOnly', choices: 'no\nyes', description: 'This will only build the application')
            choice(name: 'scanOnly', choices: 'no\nyes', description: 'This will Scan the application')
            choice(name: 'dockerPush', choices: 'no\nyes', description: 'This will build the app, docker build, docker push')
            choice(name: 'deployToDev', choices: 'no\nyes', description: 'This will Deploy the app to Dev env')
            choice(name: 'deployToTest', choices: 'no\nyes', description: 'This will Deploy the app to Test env')
            choice(name: 'deployToStage', choices: 'no\nyes', description: 'This will Deploy the app to Stage env')
            choice(name: 'deployToProd', choices: 'no\nyes', description: 'This will Deploy the app to Prod env')
        }
        environment {
            APPLICATION_NAME = "${pipelineParams.appName}"
            POM_VERSION = readMavenPom().getVersion()
            POM_PACKAGING = readMavenPom().getPackaging()
            DOCKER_HUB = "docker.io/sureshindrala"
            DOCKER_CREDS = credentials("dockerhub_creds")
            SONAR_URL = "http://34.66.190.70:9000/"
        }
        tools {
            maven 'Maven-3.8.8'
            jdk 'Jdk-17'
        }
        stages {
            stage('Authenticate to google cloud GKE') {
                steps {
                    script {
                        echo "****Executing in Google Cloud auth stage"
                    }
                }
            }
            stage('Build') {
                when {
                    anyOf {
                        expression { params.buildOnly == 'yes' }
                    }
                }
                steps {
                    script {
                        echo "********* **Executing Addition Method**********"
                        println docker.add(8, 9)
                        docker.buildApp("${env.APPLICATION_NAME}")
                    }
                }
            }
            stage('Unit Tests') {
                when {
                    anyOf {
                        expression { params.buildOnly == 'yes' }
                        expression { params.dockerPush == 'yes' }
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
            stage('Sonar') {
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
            /*
            stage('Docker Format') {
                steps {
                    echo "Actual Format: ${env.APPLICATION_NAME}-${env.POM_VERSION}-${env.POM_PACKAGING}"
                    echo "Custom Format: ${env.APPLICATION_NAME}-${currentBuild.number}-${BRANCH_NAME}.${env.POM_PACKAGING}"
                }
            }
            */
            stage('Docker Build and Push') {
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
            stage('Deploy to Dev') {
                when {
                    expression { params.deployToDev == 'yes' }
                }
                steps {
                    script {
                        imageValidation().call()
                        dockerDeploy('dev', '5761', '8761').call()
                        echo "Deployed to Dev Successfully!!!!"
                    }
                }
            }
            stage('Deploy to Test') {
                when {
                    expression { params.deployToTest == 'yes' }
                }
                steps {
                    script {
                        imageValidation().call()
                        echo "***** Entering Test Environment *****"
                        dockerDeploy('tst', '6761', '8761').call()
                    }
                }
            }
            stage('Deploy to Stage') {
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
            stage('Deploy to Prod') {
                when {
                    allOf {
                        expression { params.deployToProd == 'yes' }
                        anyOf {
                            branch 'release/*'
                        }
                    }
                }
                steps {
                    timeout(time: 300, unit: 'SECONDS') {
                        input message: "Deploying ${env.APPLICATION_NAME} to prod ????", ok: 'yes', submitter: 'suresh'
                    }
                    script {
                        imageValidation().call()
                        dockerDeploy('prod', '8761', '8761').call()
                    }
                }
            }
            stage('clean') {
                steps {
                    cleanWs()
                }
            }
        }
    }
}

def dockerBuildandPush() {
    return {
        echo "******************************** Build Docker Image ********************************"
        sh "cp ${workspace}/target/i27-${env.APPLICATION_NAME}-${env.POM_VERSION}.${env.POM_PACKAGING} ./.cicd"
        sh "ls -la ./.cicd"
        sh "docker build --force-rm --no-cache --pull --rm=true --build-arg JAR_SOURCE=i27-${env.APPLICATION_NAME}-${env.POM_VERSION}.${env.POM_PACKAGING} -t ${env.DOCKER_HUB}/${env.APPLICATION_NAME}:${GIT_COMMIT} ./.cicd"
        echo "******************************** Login to Docker Repo ********************************"
        sh "docker login -u ${DOCKER_CREDS_USR} -p ${DOCKER_CREDS_PSW}"
        echo "******************************** Docker Push ********************************"
        sh "docker push ${env.DOCKER_HUB}/${env.APPLICATION_NAME}:${GIT_COMMIT}"
        echo "Pushed the image successfully!!!"
    }
}

def dockerDeploy(envDeploy, hostPort, contPort) {
    return {
        echo "******************************** Deploying to $envDeploy Environment ********************************"
        withCredentials([usernamePassword(credentialsId: 'docker_env_creds', passwordVariable: 'PASSWORD', usernameVariable: 'USERNAME')]) {
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
    return {
        println("Pulling the docker image")
        try {
            sh "docker pull ${env.DOCKER_HUB}/${env.APPLICATION_NAME}:${GIT_COMMIT}"
        } catch (Exception e) {
            println("OOPS!, docker images with this tag is not available")
            buildApp().call()
            dockerBuildandPush().call()
        }
    }
}
