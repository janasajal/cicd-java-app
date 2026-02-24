# CI/CD Pipeline Lab on AWS — Complete Guide

**Author:** Sajal Jana  
**Date:** February 2026  
**Stack:** AWS EC2 · Minikube · Jenkins · SonarQube · Docker · Kubernetes

---

## Table of Contents

1. [Overview](#overview)
2. [Architecture](#architecture)
3. [Infrastructure Setup](#infrastructure-setup)
4. [Tool Installation](#tool-installation)
5. [Kubernetes Deployments](#kubernetes-deployments)
6. [Jenkins Configuration](#jenkins-configuration)
7. [SonarQube Configuration](#sonarqube-configuration)
8. [GitHub Repository Setup](#github-repository-setup)
9. [Jenkinsfile Pipeline](#jenkinsfile-pipeline)
10. [Application Source Code](#application-source-code)
11. [Kubernetes Manifests](#kubernetes-manifests)
12. [Troubleshooting Reference](#troubleshooting-reference)
13. [Key Lessons Learned](#key-lessons-learned)
14. [Pipeline Flow Summary](#pipeline-flow-summary)

---

## Overview

This lab implements a complete end-to-end CI/CD pipeline on AWS using a single EC2 instance running Minikube (single-node Kubernetes). The pipeline automates the full software delivery lifecycle from code commit to production deployment, with a manual approval gate before production.

### Pipeline Stages

```
GitHub Push → Jenkins → Maven Build → SonarQube Scan → Docker Build/Push → DEV Deploy → Manual Approval → PROD Deploy
```

---

## Architecture

```
┌─────────────────────────────────────────────────┐
│                  AWS EC2 (t3.xlarge)             │
│                  Ubuntu 24.04                    │
│                                                  │
│  ┌─────────────────────────────────────────┐    │
│  │         Minikube (Docker driver)         │    │
│  │                                          │    │
│  │  ┌──────────────┐  ┌─────────────────┐  │    │
│  │  │   Jenkins    │  │   SonarQube     │  │    │
│  │  │   Pod        │  │   Pod           │  │    │
│  │  │   :8080      │  │   :9000         │  │    │
│  │  └──────────────┘  └─────────────────┘  │    │
│  │                                          │    │
│  │  ┌──────────────┐  ┌─────────────────┐  │    │
│  │  │  DEV NS      │  │   PROD NS       │  │    │
│  │  │  cicd-app    │  │   cicd-app      │  │    │
│  │  └──────────────┘  └─────────────────┘  │    │
│  └─────────────────────────────────────────┘    │
│                                                  │
│  Docker Socket · kubectl binary · Port Forwards  │
└─────────────────────────────────────────────────┘
         │                        │
    GitHub Repo              DockerHub
```

### Key Design Decisions

- **Minikube with Docker driver** — runs K8s inside Docker on EC2, no separate VM needed
- **Docker socket mount** — Jenkins pod uses EC2's Docker daemon directly to build images
- **kubectl binary mount** — Jenkins pod uses EC2's kubectl to deploy to Minikube
- **PersistentVolumes with hostPath** — Jenkins and SonarQube data survives pod restarts
- **ServiceAccount with cluster-admin** — Jenkins can create namespaces and deploy workloads
- **Port forwarding** — exposes Jenkins (:8080) and SonarQube (:9000) on EC2's public IP

---

## Infrastructure Setup

### EC2 Instance Specifications

| Parameter | Value |
|---|---|
| Instance Type | t3.xlarge (4 vCPU, 16GB RAM) |
| AMI | Ubuntu 24.04 LTS |
| Storage | 50GB gp3 |
| Region | ap-south-1 (Mumbai) |

### Security Group Rules

| Port | Protocol | Source | Purpose |
|---|---|---|---|
| 22 | TCP | Your IP | SSH access |
| 8080 | TCP | 0.0.0.0/0 | Jenkins UI |
| 9000 | TCP | 0.0.0.0/0 | SonarQube UI |
| 30000-32767 | TCP | 0.0.0.0/0 | Kubernetes NodePort |

---

## Tool Installation

### Docker

```bash
sudo apt update && sudo apt upgrade -y
sudo apt install -y apt-transport-https ca-certificates curl gnupg lsb-release

curl -fsSL https://download.docker.com/linux/ubuntu/gpg | \
  sudo gpg --dearmor -o /usr/share/keyrings/docker-archive-keyring.gpg

echo "deb [arch=amd64 signed-by=/usr/share/keyrings/docker-archive-keyring.gpg] \
  https://download.docker.com/linux/ubuntu $(lsb_release -cs) stable" | \
  sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

sudo apt update && sudo apt install -y docker-ce docker-ce-cli containerd.io
sudo usermod -aG docker ubuntu
newgrp docker
```

### kubectl

```bash
curl -LO "https://dl.k8s.io/release/$(curl -L -s https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl"
sudo install -o root -g root -m 0755 kubectl /usr/local/bin/kubectl
```

### Minikube

```bash
curl -LO https://storage.googleapis.com/minikube/releases/latest/minikube-linux-amd64
sudo install minikube-linux-amd64 /usr/local/bin/minikube

# Start with resource limits appropriate for t3.xlarge
minikube start --driver=docker --cpus=2 --memory=7000 --disk-size=30g
```

---

## Kubernetes Deployments

### Namespace & RBAC

```bash
kubectl create namespace jenkins

# ServiceAccount so Jenkins can deploy to K8s
kubectl create serviceaccount jenkins-sa -n jenkins
kubectl create clusterrolebinding jenkins-sa-binding \
  --clusterrole=cluster-admin \
  --serviceaccount=jenkins:jenkins-sa
```

### PersistentVolumes

```yaml
# jenkins-pv.yaml
apiVersion: v1
kind: PersistentVolume
metadata:
  name: jenkins-pv
spec:
  capacity:
    storage: 5Gi
  accessModes:
    - ReadWriteOnce
  hostPath:
    path: /mnt/jenkins-data
---
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: jenkins-pvc
  namespace: jenkins
spec:
  accessModes:
    - ReadWriteOnce
  resources:
    requests:
      storage: 5Gi
---
apiVersion: v1
kind: PersistentVolume
metadata:
  name: sonarqube-pv
spec:
  capacity:
    storage: 5Gi
  accessModes:
    - ReadWriteOnce
  hostPath:
    path: /mnt/sonarqube-data
---
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: sonarqube-pvc
  namespace: jenkins
spec:
  accessModes:
    - ReadWriteOnce
  resources:
    requests:
      storage: 5Gi
```

### Jenkins Deployment

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: jenkins
  namespace: jenkins
spec:
  replicas: 1
  selector:
    matchLabels:
      app: jenkins
  template:
    metadata:
      labels:
        app: jenkins
    spec:
      securityContext:
        fsGroup: 1000
        runAsUser: 0
      serviceAccountName: jenkins-sa
      containers:
      - name: jenkins
        image: jenkins/jenkins:lts
        ports:
        - containerPort: 8080
        - containerPort: 50000
        volumeMounts:
        - name: jenkins-data
          mountPath: /var/jenkins_home
        - name: docker-sock
          mountPath: /var/run/docker.sock
        - name: docker-bin
          mountPath: /usr/bin/docker
        - name: kubectl-bin
          mountPath: /usr/bin/kubectl
        resources:
          requests:
            memory: "512Mi"
            cpu: "250m"
          limits:
            memory: "2Gi"
            cpu: "1000m"
      volumes:
      - name: jenkins-data
        persistentVolumeClaim:
          claimName: jenkins-pvc
      - name: docker-sock
        hostPath:
          path: /var/run/docker.sock
      - name: docker-bin
        hostPath:
          path: /usr/bin/docker
      - name: kubectl-bin
        hostPath:
          path: /usr/local/bin/kubectl
---
apiVersion: v1
kind: Service
metadata:
  name: jenkins
  namespace: jenkins
spec:
  type: NodePort
  selector:
    app: jenkins
  ports:
  - name: http
    port: 8080
    targetPort: 8080
    nodePort: 30080
  - name: agent
    port: 50000
    targetPort: 50000
    nodePort: 30050
```

### SonarQube Deployment

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: sonarqube
  namespace: jenkins
spec:
  replicas: 1
  selector:
    matchLabels:
      app: sonarqube
  template:
    metadata:
      labels:
        app: sonarqube
    spec:
      containers:
      - name: sonarqube
        image: sonarqube:lts-community
        ports:
        - containerPort: 9000
        env:
        - name: SONAR_ES_BOOTSTRAP_CHECKS_DISABLE
          value: "true"
        volumeMounts:
        - name: sonarqube-data
          mountPath: /opt/sonarqube/data
        resources:
          requests:
            memory: "512Mi"
            cpu: "250m"
          limits:
            memory: "2Gi"
            cpu: "1000m"
      volumes:
      - name: sonarqube-data
        persistentVolumeClaim:
          claimName: sonarqube-pvc
---
apiVersion: v1
kind: Service
metadata:
  name: sonarqube
  namespace: jenkins
spec:
  type: NodePort
  selector:
    app: sonarqube
  ports:
  - port: 9000
    targetPort: 9000
    nodePort: 30090
```

### Port Forwarding (Access from Public IP)

```bash
# Start background port forwards
nohup kubectl port-forward svc/jenkins 8080:8080 -n jenkins --address 0.0.0.0 \
  > /tmp/jenkins-pf.log 2>&1 &

nohup kubectl port-forward svc/sonarqube 9000:9000 -n jenkins --address 0.0.0.0 \
  > /tmp/sonar-pf.log 2>&1 &

# Stop port forwards
pkill -f port-forward
```

### Fix kubectl Permissions Inside Jenkins Pod

```bash
# Copy kubectl into the running pod (avoids hostPath permission issues)
kubectl cp /usr/local/bin/kubectl \
  jenkins/$(kubectl get pods -n jenkins | grep jenkins | grep -v sonar | awk '{print $1}'):/usr/local/bin/kubectl

kubectl exec -n jenkins \
  $(kubectl get pods -n jenkins | grep jenkins | grep -v sonar | awk '{print $1}') \
  -- chmod 755 /usr/local/bin/kubectl
```

---

## Jenkins Configuration

### Plugins to Install

Navigate to **Manage Jenkins → Plugins → Available plugins** and install:

- `SonarQube Scanner`
- `Docker Pipeline`
- `Kubernetes CLI`
- `Pipeline Stage View`

### Credentials

Navigate to **Manage Jenkins → Credentials → System → Global credentials**:

| ID | Kind | Value |
|---|---|---|
| `dockerhub-creds` | Username with password | DockerHub username + PAT token |
| `sonar-token` | Secret text | SonarQube generated token |

### Tools Configuration

**Manage Jenkins → Tools:**

- Maven: Name=`maven`, Version=`3.9.6`, Install automatically ✅

### SonarQube Server

**Manage Jenkins → System → SonarQube servers:**

- Name: `sonarqube`
- URL: `http://192.168.49.2:30090`
- Token: select `sonar-token` credential

---

## SonarQube Configuration

1. Access at `http://<EC2-IP>:9000`
2. Login: `admin` / `admin` → change password on first login
3. Navigate to **My Account → Security → Generate Token**
4. Name: `jenkins-token`, Type: `Global Analysis Token`
5. Copy the generated token → add to Jenkins credentials as `sonar-token`

---

## GitHub Repository Setup

### SSH Key Authentication

```bash
# Generate key on EC2
ssh-keygen -t ed25519 -C "jenkins-cicd" -f ~/.ssh/id_ed25519 -N ""

# Print public key — copy this to GitHub
cat ~/.ssh/id_ed25519.pub

# Test connection
ssh -T git@github.com

# Set repo remote to SSH
cd ~/cicd-java-app
git remote set-url origin git@github.com:janasajal/cicd-java-app.git
```

Add the public key to **GitHub → Settings → SSH and GPG keys → New SSH key**.

### Repository Structure

```
cicd-java-app/
├── src/
│   ├── main/java/com/example/App.java
│   └── test/java/com/example/AppTest.java
├── k8s/
│   └── deployment.yaml
├── Dockerfile
├── Jenkinsfile
└── pom.xml
```

---

## Application Source Code

### App.java

```java
package com.example;

public class App {
    public static void main(String[] args) {
        System.out.println("Hello from CI/CD Pipeline!");
    }

    public String getMessage() {
        return "Hello from CI/CD Pipeline!";
    }
}
```

### AppTest.java

```java
package com.example;

import org.junit.Test;
import static org.junit.Assert.*;

public class AppTest {
    @Test
    public void testGetMessage() {
        App app = new App();
        assertEquals("Hello from CI/CD Pipeline!", app.getMessage());
    }
}
```

### pom.xml

```xml
<project>
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.example</groupId>
  <artifactId>cicd-java-app</artifactId>
  <version>1.0-SNAPSHOT</version>

  <dependencies>
    <dependency>
      <groupId>junit</groupId>
      <artifactId>junit</artifactId>
      <version>4.13.2</version>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.sonarsource.scanner.maven</groupId>
        <artifactId>sonar-maven-plugin</artifactId>
        <version>3.9.1.2184</version>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId>
        <configuration>
          <source>11</source>
          <target>11</target>
        </configuration>
      </plugin>
    </plugins>
  </build>
</project>
```

### Dockerfile

```dockerfile
FROM eclipse-temurin:11-jre-jammy
WORKDIR /app
COPY target/cicd-java-app-1.0-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

> **Note:** `openjdk:11-jre-slim` was removed from DockerHub. Use `eclipse-temurin:11-jre-jammy` instead.

---

## Kubernetes Manifests

### k8s/deployment.yaml

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: cicd-java-app
  namespace: NAMESPACE_PLACEHOLDER
spec:
  replicas: 1
  selector:
    matchLabels:
      app: cicd-java-app
  template:
    metadata:
      labels:
        app: cicd-java-app
    spec:
      containers:
      - name: cicd-java-app
        image: IMAGE_PLACEHOLDER
        ports:
        - containerPort: 8080
---
apiVersion: v1
kind: Service
metadata:
  name: cicd-java-app
  namespace: NAMESPACE_PLACEHOLDER
spec:
  type: NodePort
  selector:
    app: cicd-java-app
  ports:
  - port: 8080
    targetPort: 8080
```

> **Note:** Do not set a fixed `nodePort` value — let Kubernetes auto-assign to avoid conflicts between DEV and PROD namespaces.

---

## Jenkinsfile Pipeline

```groovy
pipeline {
    agent any
    tools {
        maven 'maven'
    }
    environment {
        DOCKERHUB_CREDENTIALS = credentials('dockerhub-creds')
        SONAR_TOKEN           = credentials('sonar-token')
        DOCKERHUB_USERNAME    = 'janasajal'
        IMAGE_NAME            = 'janasajal/cicd-java-app'
        IMAGE_TAG             = "${BUILD_NUMBER}"
        SONAR_HOST_URL        = 'http://192.168.49.2:30090'
    }
    stages {
        stage('Maven Build & Test') {
            steps {
                sh 'mvn clean package'
            }
            post {
                always {
                    junit allowEmptyResults: true,
                          testResults: 'target/surefire-reports/*.xml'
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
```

---

## Troubleshooting Reference

### Common Issues & Fixes

| Error | Cause | Fix |
|---|---|---|
| `mvn: not found` | Maven not in PATH | Add `tools { maven 'maven' }` block to Jenkinsfile |
| `docker: not found` | Docker socket not mounted | Mount `/var/run/docker.sock` and `/usr/bin/docker` as hostPath volumes |
| `kubectl: not found` | kubectl not in Jenkins container | Copy binary into pod: `kubectl cp /usr/local/bin/kubectl jenkins/<pod>:/usr/local/bin/kubectl` |
| `kubectl: Permission denied` | hostPath mount overrides permissions | Use `kubectl cp` to copy binary directly into pod, then `chmod 755` |
| `manifest unknown` for `openjdk:11-jre-slim` | Image removed from DockerHub | Use `eclipse-temurin:11-jre-jammy` instead |
| `nodePort already allocated` | DEV and PROD share same port | Remove fixed `nodePort` from Service, let K8s auto-assign |
| `fatal: not in a git directory` | Corrupt workspace | Delete workspace: `rm -rf /var/jenkins_home/workspace/cicd-java-pipeline*` |
| Jenkins pod `Pending` — Insufficient CPU | Resource requests too high | Lower `requests.cpu` to `250m` |
| Port forward dies after pod restart | Process killed | Re-run `nohup kubectl port-forward ...` commands |
| Data lost after pod restart | Using `emptyDir` storage | Use PersistentVolumeClaims backed by hostPath PVs |

### Useful Commands

```bash
# Check pod status
kubectl get pods -n jenkins

# Check pod logs
kubectl logs -n jenkins <pod-name>

# Describe pod (see events/errors)
kubectl describe pod -n jenkins <pod-name>

# Check PVC binding
kubectl get pvc -n jenkins

# Restart a deployment
kubectl rollout restart deployment/jenkins -n jenkins

# Get Jenkins initial admin password
kubectl exec -n jenkins <pod-name> -- cat /var/jenkins_home/secrets/initialAdminPassword

# Verify docker works inside Jenkins pod
kubectl exec -n jenkins <pod-name> -- docker ps

# Verify kubectl works inside Jenkins pod
kubectl exec -n jenkins <pod-name> -- kubectl version --client

# Check DEV/PROD deployments
kubectl get all -n dev
kubectl get all -n prod
```

---

## Key Lessons Learned

### 1. Docker-in-Docker via Socket Mount
Running Docker commands inside a Kubernetes pod requires mounting the host's Docker socket (`/var/run/docker.sock`). This gives the pod access to the EC2's Docker daemon rather than needing a separate Docker installation.

### 2. PersistentVolumes Are Essential
Using `emptyDir` for Jenkins and SonarQube means all data (jobs, plugins, credentials, config) is lost whenever the pod restarts. Always use PVCs backed by `hostPath` PVs in this setup.

### 3. hostPath Binary Mounts vs kubectl cp
Mounting binaries like `docker` and `kubectl` via `hostPath` can cause permission issues because the mount overrides the container's filesystem permissions. Copying the binary directly into the pod using `kubectl cp` followed by `chmod 755` is more reliable.

### 4. Namespace Isolation for Environments
Using separate Kubernetes namespaces (`dev` and `prod`) provides environment isolation. Always use `--dry-run=client -o yaml | kubectl apply -f -` for idempotent namespace creation.

### 5. NodePort Conflicts
When deploying the same Service manifest to multiple namespaces, avoid hardcoding `nodePort` values. Let Kubernetes auto-assign ports to prevent conflicts.

### 6. Base Image Availability
`openjdk:11-jre-slim` was deprecated and removed from DockerHub. Always use actively maintained base images like `eclipse-temurin` from Adoptium.

### 7. Resource Management on Small Clusters
On a single-node Minikube cluster with limited RAM, setting low resource `requests` (e.g., `250m` CPU, `512Mi` memory) while keeping higher `limits` allows pods to schedule without starving each other.

### 8. Groovy String Interpolation Security
Using `${VARIABLE}` inside a `sh` block with secret credentials leaks them in logs. Jenkins warns about this. For production, use `withCredentials` blocks or single-quoted strings where possible.

---

## Pipeline Flow Summary

```
┌─────────────┐
│  Developer  │
│  git push   │
└──────┬──────┘
       │
       ▼
┌─────────────────────────────────────────────────────┐
│                    Jenkins Pipeline                  │
│                                                     │
│  Stage 1: Maven Build & Test                        │
│  ├── mvn clean package                              │
│  └── JUnit test report published                    │
│                                                     │
│  Stage 2: SonarQube Code Scan                       │
│  ├── mvn sonar:sonar                                │
│  └── Results at http://192.168.49.2:30090           │
│                                                     │
│  Stage 3: Docker Build & Push                       │
│  ├── docker build -t janasajal/cicd-java-app:N .    │
│  ├── docker push janasajal/cicd-java-app:N          │
│  └── docker push janasajal/cicd-java-app:latest     │
│                                                     │
│  Stage 4: Deploy to DEV                             │
│  ├── kubectl create namespace dev                   │
│  └── kubectl apply (Deployment + Service)           │
│                                                     │
│  Stage 5: Manual Approval Gate ⏸                    │
│  └── Human clicks "Approve" in Jenkins UI           │
│                                                     │
│  Stage 6: Deploy to PROD                            │
│  ├── kubectl create namespace prod                  │
│  └── kubectl apply (Deployment + Service)           │
└─────────────────────────────────────────────────────┘
       │                    │
       ▼                    ▼
┌──────────────┐    ┌──────────────┐
│  DEV NS      │    │  PROD NS     │
│  cicd-java   │    │  cicd-java   │
│  -app pod    │    │  -app pod    │
└──────────────┘    └──────────────┘
```

### Final Stage Results

| Stage | Status | Notes |
|---|---|---|
| Maven Build & Test | ✅ SUCCESS | 1 test passed, JAR built |
| SonarQube Code Scan | ✅ SUCCESS | Analysis uploaded, dashboard updated |
| Docker Build & Push | ✅ SUCCESS | Image pushed to DockerHub |
| Deploy to DEV | ✅ SUCCESS | Pod running in `dev` namespace |
| Manual Approval | ✅ APPROVED | Human gate passed |
| Deploy to PROD | ✅ SUCCESS | Pod running in `prod` namespace |

---

*Document prepared by Sajal Jana — CI/CD Pipeline Lab, February 2026*
