pipeline {
    agent any
    tools {
        maven 'maven'
    }
    environment {
        DOCKERHUB_CREDENTIALS = credentials('dockerhub-creds')
        SONAR_TOKEN = credentials('sonar-token')
        DOCKERHUB_USERNAME = 'janasajal'
        IMAGE_NAME = 'janasajal/cicd-java-app'
        IMAGE_TAG = "${BUILD_NUMBER}"
        SONAR_HOST_URL = 'http://192.168.49.2:30090'
    }
    stages {
        stage('Maven Build & Test') {
            steps {
                sh 'mvn clean package'
            }
            post {
                always {
                    junit allowEmptyResults: true, testResults: 'target/surefire-reports/*.xml'
                }
            }
        }
        stage('SonarQube Code Scan') {
            steps {
                sh """
                    mvn sonar:sonar \
                        -Dsonar.projectKey=cicd-java-app \
                        -Dsonar.host.url=${SONAR_HOST_URL} \
                        -Dsonar.login=${SONAR_TOKEN}
                """
            }
        }
        stage('Docker Build & Push') {
            steps {
                sh """
                    echo ${DOCKERHUB_CREDENTIALS_PSW} | docker login -u ${DOCKERHUB_CREDENTIALS_USR} --password-stdin
                    docker build -t ${IMAGE_NAME}:${IMAGE_TAG} .
                    docker push ${IMAGE_NAME}:${IMAGE_TAG}
                    docker tag ${IMAGE_NAME}:${IMAGE_TAG} ${IMAGE_NAME}:latest
                    docker push ${IMAGE_NAME}:latest
                """
            }
        }
        stage('Deploy to DEV') {
            steps {
                sh """
                    kubectl create namespace dev --dry-run=client -o yaml | kubectl apply -f -
                    sed 's|IMAGE_PLACEHOLDER|${IMAGE_NAME}:${IMAGE_TAG}|g' k8s/deployment.yaml | \
                    sed 's|NAMESPACE_PLACEHOLDER|dev|g' | kubectl apply -f -
                """
            }
        }
        stage('Manual Approval') {
            steps {
                timeout(time: 5, unit: 'MINUTES') {
                    input message: 'Deploy to PROD?', ok: 'Approve'
                }
            }
        }
        stage('Deploy to PROD') {
            steps {
                sh """
                    kubectl create namespace prod --dry-run=client -o yaml | kubectl apply -f -
                    sed 's|IMAGE_PLACEHOLDER|${IMAGE_NAME}:${IMAGE_TAG}|g' k8s/deployment.yaml | \
                    sed 's|NAMESPACE_PLACEHOLDER|prod|g' | kubectl apply -f -
                """
            }
        }
    }
    post {
        success { echo 'Pipeline succeeded!' }
        failure { echo 'Pipeline failed!' }
    }
}
