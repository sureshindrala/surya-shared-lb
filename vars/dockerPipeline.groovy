import com.i27academy.builds.Docker

def call(Map pipelineParams) {
    Docker docker = new Docker(this)
    pipeline {
        agent {
            label 'k8s-slave'
        }
        parameters {
            choice(name: 'buildOnly',
                choices: 'no\nyes',
                description: 'This will only build the application'
            )
            choice(name: 'scanOnly',
                choices: 'no\nyes',
                description: 'This will Scan the application'
            )
            choice(name: 'dockerPush',
                choices: 'no\nyes',
                description: 'This will build the app, docker build, docker push'
            )
            choice(name: 'deployToDev',
                choices: 'no\nyes',
                description: 'This will Deploy the app to Dev env'
            )
            choice(name: 'deployToTest',
                choices: 'no\nyes',
                description: 'This will Deploy the app to Test env'
            )
            choice(name: 'deployToStage',
                choices: 'no\nyes',
                description: 'This will Deploy the app to Stage env'
            )
            choice(name: 'deployToProd',
                choices: 'no\nyes',
                description: 'This will Deploy the app to Prod env'
            )
        }
        environment {
            APPLICATION_NAME = "eureka"
            POM_VERSION = readMavenPom().getVersion()
            POM_PACKAGING = readMavenPom().getPackaging()
            // version + packaging
            DOCKER_HUB = "docker.io/sureshindrala"
            DOCKER_CREDS = credentials("dockerhub_creds")
            SONAR_URL = "http://34.66.190.70:9000/"
        }
        tools {
            maven 'Maven-3.8.8'
            jdk 'Jdk-17'
        }
        stages {
            stage('Build') {
                when {
                    expression {
                        params.buildOnly == 'yes'
                    }
                }
                steps {
                    script {
                        buildApp().call()
                    }
                }
            }
            stage('Unit Tests') {
                when {
                    expression {
                        params.buildOnly == 'yes' || params.dockerPush == 'yes'
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
                    expression {
                        params.scanOnly == 'yes'
                    }
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
            stage('Docker Build and Push') {
                when {
                    expression {
                        params.dockerPush == 'yes'
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
                    expression {
                        params.deployToDev == 'yes'
                    }
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
                    expression {
                        params.deployToTest == 'yes'
                    }
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
                    expression {
                        params.deployToStage == 'yes'
                    }
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
                        expression {
                            params.deployToProd == 'yes'
                        }
                        anyOf {
                            branch 'release/*'
                            // only tags with vx.x.x should deploy to prod
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

// Define buildApp function


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

// This method is developed for Deploying our App in different environments
def dockerDeploy(envDeploy, hostPort, contPort) {
    return {
        echo "******************************** Deploying to $envDeploy Environment ********************************"
        withCredentials([usernamePassword(credentialsId: 'docker_env_creds', passwordVariable: 'PASSWORD', usernameVariable: 'USERNAME')]) {
            script {
                // Pull the image on the Docker Server
                sh "sshpass -p ${PASSWORD} -v ssh -o StrictHostKeyChecking=no ${USERNAME}@${docker_server_ip} docker pull ${env.DOCKER_HUB}/${env.APPLICATION_NAME}:${GIT_COMMIT}"

                try {
                    // Stop the Container
                    echo "Stopping the Container"
                    sh "sshpass -p ${PASSWORD} -v ssh -o StrictHostKeyChecking=no ${USERNAME}@${docker_server_ip} docker stop ${env.APPLICATION_NAME}-$envDeploy"

                    // Remove the Container 
                    echo "Removing the Container"
                    sh "sshpass -p ${PASSWORD} -v ssh -o StrictHostKeyChecking=no ${USERNAME}@${docker_server_ip} docker rm ${env.APPLICATION_NAME}-$envDeploy"
                } catch (err) {
                    echo "Caught the Error: $err"
                }

                // Create a Container 
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
            println("OOPS!, docker image with this tag is not available")
            buildApp().call()
            dockerBuildandPush().call()
        }
    }
}
