# CI/CD Pipeline Lab on AWS — Complete Guide with ArgoCD GitOps

**Author:** Sajal Jana  
**Date:** February 2026  
**Stack:** AWS EC2 · Minikube · Jenkins · SonarQube · Docker · Kubernetes · ArgoCD

---

## Table of Contents

1. [Overview](#overview)
2. [Architecture](#architecture)
3. [Infrastructure Setup](#infrastructure-setup)
4. [Tool Installation](#tool-installation)
5. [Kubernetes Deployments](#kubernetes-deployments)
6. [ArgoCD Installation on Minikube](#argocd-installation-on-minikube)
7. [Jenkins Configuration](#jenkins-configuration)
8. [SonarQube Configuration](#sonarqube-configuration)
9. [GitHub Repository Setup](#github-repository-setup)
10. [GitOps Repository Structure](#gitops-repository-structure)
11. [Application Source Code](#application-source-code)
12. [Kubernetes Manifests](#kubernetes-manifests)
13. [ArgoCD Application Manifests](#argocd-application-manifests)
14. [Jenkinsfile Pipeline](#jenkinsfile-pipeline)
15. [Troubleshooting Reference](#troubleshooting-reference)
16. [Key Lessons Learned](#key-lessons-learned)
17. [Pipeline Flow Summary](#pipeline-flow-summary)

---

## Overview

This lab implements a complete end-to-end CI/CD pipeline on AWS using a single EC2 instance running Minikube (single-node Kubernetes). It follows the **GitOps** pattern:

- **Jenkins** owns CI — build, test, scan, package, and update Git manifests
- **ArgoCD** owns CD — it fully replaces `kubectl apply` and is triggered by Jenkins via the ArgoCD REST API after each approval gate

There are **no direct `kubectl apply` commands** in the pipeline. All deployments are driven through ArgoCD.

### Pipeline Stages

```
GitHub Push
    │
    ▼
Jenkins (CI)
    ├── Stage 1: Maven Build & Test
    ├── Stage 2: SonarQube Code Scan
    ├── Stage 3: Docker Build & Push to DockerHub
    ├── Stage 4: Update image tag in Git (kustomization.yaml)
    ├── Stage 5: Trigger ArgoCD API → Sync DEV
    ├── Stage 6: Manual Approval Gate
    ├── Stage 7: Update PROD image tag in Git
    └── Stage 8: Trigger ArgoCD API → Sync PROD
```

### Why ArgoCD Instead of kubectl?

| Concern | kubectl deploy (old) | ArgoCD deploy (new) |
|---|---|---|
| Deployment trigger | Jenkins runs kubectl | Jenkins calls ArgoCD API |
| Source of truth | Jenkins pipeline | Git repository |
| Drift detection | None | ArgoCD self-heals drift |
| Rollback | Manual kubectl | One-click in ArgoCD UI |
| Audit trail | Jenkins logs | Git commit history |
| Visibility | None | ArgoCD UI with health status |

---

## Architecture

```
┌────────────────────────────────────────────────────────────────┐
│                     AWS EC2 (t3.xlarge)                         │
│                     Ubuntu 24.04                                │
│                                                                  │
│  ┌────────────────────────────────────────────────────────┐    │
│  │                Minikube (Docker driver)                  │    │
│  │                                                          │    │
│  │  ┌────────────┐  ┌─────────────┐  ┌────────────────┐   │    │
│  │  │  Jenkins   │  │  SonarQube  │  │    ArgoCD      │   │    │
│  │  │  :8080     │  │  :9000      │  │    :8085       │   │    │
│  │  └────────────┘  └─────────────┘  └────────────────┘   │    │
│  │        │                                  │              │    │
│  │        │  1. Calls ArgoCD REST API        │              │    │
│  │        │─────────────────────────────────>│              │    │
│  │        │                                  │              │    │
│  │        │              2. ArgoCD syncs K8s │              │    │
│  │        │                        ┌─────────┘              │    │
│  │        │                        ▼                        │    │
│  │        │         ┌──────────────────────────┐            │    │
│  │        │         │  DEV NS   │   PROD NS    │            │    │
│  │        │         │  cicd-app │   cicd-app   │            │    │
│  │        │         └──────────────────────────┘            │    │
│  └────────────────────────────────────────────────────────┘    │
│                                                                  │
│  Docker Socket · Port Forwards (8080, 9000, 8085)               │
└────────────────────────────────────────────────────────────────┘
         │                    │                   │
    GitHub Repo           DockerHub          ArgoCD watches
    (app + k8s            (images)           GitHub for
     manifests)                              manifest changes
```

---

## Infrastructure Setup

### EC2 Instance Specifications

| Parameter | Value |
|---|---|
| Instance Type | t3.xlarge (4 vCPU, 16GB RAM) |
| AMI | Ubuntu 24.04 LTS |
| Storage | 50GB gp3 |

### Security Group Rules

| Port | Source | Purpose |
|---|---|---|
| 22 | Your IP | SSH |
| 8080 | 0.0.0.0/0 | Jenkins UI |
| 9000 | 0.0.0.0/0 | SonarQube UI |
| 8085 | 0.0.0.0/0 | ArgoCD UI |
| 30000-32767 | 0.0.0.0/0 | Kubernetes NodePort |

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
curl -LO "https://dl.k8s.io/release/$(curl -L -s \
  https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl"
sudo install -o root -g root -m 0755 kubectl /usr/local/bin/kubectl
```

### Minikube

```bash
curl -LO https://storage.googleapis.com/minikube/releases/latest/minikube-linux-amd64
sudo install minikube-linux-amd64 /usr/local/bin/minikube

minikube start --driver=docker --cpus=2 --memory=7000 --disk-size=30g
```

---

## Kubernetes Deployments

### Namespaces & RBAC

```bash
kubectl create namespace jenkins
kubectl create namespace argocd
kubectl create namespace dev
kubectl create namespace prod

# ServiceAccount for Jenkins pod
kubectl create serviceaccount jenkins-sa -n jenkins
kubectl create clusterrolebinding jenkins-sa-binding \
  --clusterrole=cluster-admin \
  --serviceaccount=jenkins:jenkins-sa
```

### PersistentVolumes

```yaml
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

> **Note:** Jenkins no longer needs a mounted `kubectl` binary because it no longer runs `kubectl apply`. It only needs Docker (to build images) and `curl` (to call the ArgoCD API).

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

### Port Forwarding

```bash
# Start all port forwards in background
nohup kubectl port-forward svc/jenkins 8080:8080 -n jenkins --address 0.0.0.0 \
  > /tmp/jenkins-pf.log 2>&1 &

nohup kubectl port-forward svc/sonarqube 9000:9000 -n jenkins --address 0.0.0.0 \
  > /tmp/sonar-pf.log 2>&1 &

nohup kubectl port-forward svc/argocd-server 8085:443 -n argocd --address 0.0.0.0 \
  > /tmp/argocd-pf.log 2>&1 &

# Stop all
pkill -f port-forward
```

---

## ArgoCD Installation on Minikube

### Step 1 — Install ArgoCD

```bash
# Install ArgoCD into its own namespace
kubectl apply -n argocd \
  -f https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml

# Wait for all pods to be ready (may take 2-3 minutes)
kubectl wait --for=condition=Ready pods --all -n argocd --timeout=300s

# Verify
kubectl get pods -n argocd
```

Expected pods running:

```
argocd-application-controller-0       1/1   Running
argocd-applicationset-controller-xxx  1/1   Running
argocd-dex-server-xxx                 1/1   Running
argocd-notifications-controller-xxx   1/1   Running
argocd-redis-xxx                      1/1   Running
argocd-repo-server-xxx                1/1   Running
argocd-server-xxx                     1/1   Running
```

### Step 2 — Get Admin Password

```bash
kubectl -n argocd get secret argocd-initial-admin-secret \
  -o jsonpath="{.data.password}" | base64 -d && echo
```

### Step 3 — Access ArgoCD UI

```bash
# Port forward
nohup kubectl port-forward svc/argocd-server 8085:443 -n argocd --address 0.0.0.0 \
  > /tmp/argocd-pf.log 2>&1 &
```

Open `https://<EC2-IP>:8085` — Login: `admin` / `<password from step 2>`

### Step 4 — Generate ArgoCD API Token for Jenkins

Jenkins will call ArgoCD's REST API to trigger syncs. It needs an API token.

In ArgoCD UI:
1. Go to **Settings → Accounts**
2. Click **New Account** → Name: `jenkins`, enable **apiKey** capability → Save
3. Go to **Settings → Accounts → jenkins**
4. Click **Generate New Token** → copy the token

Or via CLI:

```bash
# Install ArgoCD CLI on EC2
curl -sSL -o /usr/local/bin/argocd \
  https://github.com/argoproj/argo-cd/releases/latest/download/argocd-linux-amd64
chmod +x /usr/local/bin/argocd

# Login
argocd login localhost:8085 \
  --username admin \
  --password <your-admin-password> \
  --insecure

# Create jenkins account and generate token
argocd account generate-token --account jenkins
```

Copy the generated token — add it to Jenkins credentials as `argocd-api-token` (Secret text).

### Step 5 — Grant Jenkins Account Permissions

```bash
# Give jenkins account permission to sync applications
kubectl patch configmap argocd-cm -n argocd --patch '
data:
  accounts.jenkins: apiKey
'

kubectl patch configmap argocd-rbac-cm -n argocd --patch '
data:
  policy.csv: |
    p, role:jenkins, applications, sync, */*, allow
    p, role:jenkins, applications, get, */*, allow
    g, jenkins, role:jenkins
  policy.default: role:readonly
'
```

### Step 6 — Apply ArgoCD Applications

```bash
# Apply both Application manifests (see ArgoCD Application Manifests section)
kubectl apply -f argocd/app-dev.yaml
kubectl apply -f argocd/app-prod.yaml

# Verify
kubectl get applications -n argocd
```

---

## Jenkins Configuration

### Plugins to Install

**Manage Jenkins → Plugins → Available plugins:**

- `SonarQube Scanner`
- `Docker Pipeline`
- `Pipeline Stage View`
- `HTTP Request` ← needed to call ArgoCD REST API

### Credentials

**Manage Jenkins → Credentials → System → Global credentials:**

| ID | Kind | Value |
|---|---|---|
| `dockerhub-creds` | Username with password | DockerHub username + PAT token |
| `sonar-token` | Secret text | SonarQube generated token |
| `github-token` | Username with password | GitHub username + Personal Access Token |
| `argocd-api-token` | Secret text | ArgoCD API token for `jenkins` account |

### Tools Configuration

**Manage Jenkins → Tools:**

- Maven: Name=`maven`, Version=`3.9.6`, Install automatically ✅

### SonarQube Server

**Manage Jenkins → System → SonarQube servers:**

- Name: `sonarqube`
- URL: `http://192.168.49.2:30090`
- Token: select `sonar-token`

---

## SonarQube Configuration

1. Access `http://<EC2-IP>:9000`
2. Login: `admin` / `admin` → change password on first login
3. **My Account → Security → Generate Token**
4. Name: `jenkins-token`, Type: `Global Analysis Token`
5. Copy token → add to Jenkins credentials as `sonar-token`

---

## GitHub Repository Setup

### SSH Key Authentication

```bash
# Generate SSH key on EC2
ssh-keygen -t ed25519 -C "jenkins-cicd" -f ~/.ssh/id_ed25519 -N ""

# Print public key — copy to GitHub → Settings → SSH Keys
cat ~/.ssh/id_ed25519.pub

# Test
ssh -T git@github.com

# Set remote to SSH
cd ~/cicd-java-app
git remote set-url origin git@github.com:janasajal/cicd-java-app.git
```

### GitHub Personal Access Token

Jenkins needs a PAT to push image tag updates back to the repository. Create one at **GitHub → Settings → Developer Settings → Personal Access Tokens → Tokens (classic)** with `repo` scope. Add to Jenkins credentials as `github-token`.

---

## GitOps Repository Structure

In the GitOps model the repository holds both application source code and Kubernetes manifests. Jenkins is responsible for committing the new image tag into the manifests. ArgoCD watches the repository and syncs the cluster when it detects a change.

```
cicd-java-app/
├── src/
│   ├── main/java/com/example/App.java
│   └── test/java/com/example/AppTest.java
├── k8s/
│   ├── base/
│   │   ├── deployment.yaml          # Base deployment (no image tag)
│   │   ├── service.yaml             # Base service
│   │   └── kustomization.yaml       # Lists base resources
│   └── overlays/
│       ├── dev/
│       │   └── kustomization.yaml   # DEV image tag ← Jenkins updates this
│       └── prod/
│           └── kustomization.yaml   # PROD image tag ← Jenkins updates this
├── argocd/
│   ├── app-dev.yaml                 # ArgoCD Application for DEV
│   └── app-prod.yaml                # ArgoCD Application for PROD
├── Dockerfile
├── Jenkinsfile
└── pom.xml
```

### What Jenkins Writes to Git

After building and pushing Docker image tag `BUILD_NUMBER`, Jenkins runs:

```bash
# Update DEV overlay
sed -i "s|newTag:.*|newTag: \"${BUILD_NUMBER}\"|g" \
  k8s/overlays/dev/kustomization.yaml

git commit -am "CI: update DEV image to ${BUILD_NUMBER} [skip ci]"
git push origin main
```

Then calls ArgoCD API to sync immediately without waiting for the poll interval.

> **`[skip ci]`** in the commit message prevents GitHub from triggering another Jenkins build, avoiding an infinite loop.

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

> **Note:** `openjdk:11-jre-slim` was removed from DockerHub. Use `eclipse-temurin:11-jre-jammy`.

---

## Kubernetes Manifests

### k8s/base/deployment.yaml

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: cicd-java-app
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
        image: janasajal/cicd-java-app   # Kustomize overlays set the tag
        ports:
        - containerPort: 8080
```

### k8s/base/service.yaml

```yaml
apiVersion: v1
kind: Service
metadata:
  name: cicd-java-app
spec:
  type: NodePort
  selector:
    app: cicd-java-app
  ports:
  - port: 8080
    targetPort: 8080
    # No fixed nodePort — Kubernetes auto-assigns to avoid conflicts
```

### k8s/base/kustomization.yaml

```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
resources:
  - deployment.yaml
  - service.yaml
```

### k8s/overlays/dev/kustomization.yaml

```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
namespace: dev
resources:
  - ../../base
images:
  - name: janasajal/cicd-java-app
    newTag: "latest"    # Jenkins updates this value on every build
```

### k8s/overlays/prod/kustomization.yaml

```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
namespace: prod
resources:
  - ../../base
images:
  - name: janasajal/cicd-java-app
    newTag: "latest"    # Jenkins updates this value after manual approval
```

---

## ArgoCD Application Manifests

### argocd/app-dev.yaml

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: cicd-java-app-dev
  namespace: argocd
  finalizers:
    - resources-finalizer.argocd.argoproj.io
spec:
  project: default
  source:
    repoURL: https://github.com/janasajal/cicd-java-app.git
    targetRevision: main
    path: k8s/overlays/dev          # Points to DEV kustomize overlay
  destination:
    server: https://kubernetes.default.svc
    namespace: dev
  syncPolicy:
    automated:
      prune: true       # Delete K8s resources removed from Git
      selfHeal: true    # Revert any manual changes back to Git state
    syncOptions:
      - CreateNamespace=true
```

### argocd/app-prod.yaml

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: cicd-java-app-prod
  namespace: argocd
  finalizers:
    - resources-finalizer.argocd.argoproj.io
spec:
  project: default
  source:
    repoURL: https://github.com/janasajal/cicd-java-app.git
    targetRevision: main
    path: k8s/overlays/prod         # Points to PROD kustomize overlay
  destination:
    server: https://kubernetes.default.svc
    namespace: prod
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
    syncOptions:
      - CreateNamespace=true
```

### How ArgoCD Sync Is Triggered by Jenkins

Jenkins does **not** wait for ArgoCD's polling interval (default 3 minutes). Instead it calls the ArgoCD REST API directly to trigger an immediate sync:

```bash
# Trigger sync via ArgoCD REST API
curl -k -X POST \
  -H "Authorization: Bearer ${ARGOCD_TOKEN}" \
  -H "Content-Type: application/json" \
  https://localhost:8085/api/v1/applications/cicd-java-app-dev/sync \
  -d '{}'

# Poll sync status until Synced + Healthy
curl -k \
  -H "Authorization: Bearer ${ARGOCD_TOKEN}" \
  https://localhost:8085/api/v1/applications/cicd-java-app-dev \
  | python3 -c "import sys,json; \
      app=json.load(sys.stdin); \
      print(app['status']['sync']['status'], \
            app['status']['health']['status'])"
```

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
        GITHUB_CREDENTIALS    = credentials('github-token')
        ARGOCD_TOKEN          = credentials('argocd-api-token')
        IMAGE_NAME            = 'janasajal/cicd-java-app'
        IMAGE_TAG             = "${BUILD_NUMBER}"
        SONAR_HOST_URL        = 'http://192.168.49.2:30090'
        ARGOCD_SERVER         = 'https://localhost:8085'
        GIT_REPO_HTTPS        = 'https://github.com/janasajal/cicd-java-app.git'
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
                    echo ${DOCKERHUB_CREDENTIALS_PSW} | \
                      docker login -u ${DOCKERHUB_CREDENTIALS_USR} --password-stdin
                    docker build -t ${IMAGE_NAME}:${IMAGE_TAG} .
                    docker push ${IMAGE_NAME}:${IMAGE_TAG}
                    docker tag ${IMAGE_NAME}:${IMAGE_TAG} ${IMAGE_NAME}:latest
                    docker push ${IMAGE_NAME}:latest
                """
            }
        }

        stage('Update DEV Image Tag in Git') {
            steps {
                sh """
                    git config user.email "jenkins@cicd.local"
                    git config user.name "Jenkins CI"

                    sed -i 's|newTag:.*|newTag: "${IMAGE_TAG}"|g' \
                      k8s/overlays/dev/kustomization.yaml

                    git add k8s/overlays/dev/kustomization.yaml
                    git commit -m "CI: update DEV image tag to ${IMAGE_TAG} [skip ci]"

                    git push https://${GITHUB_CREDENTIALS_USR}:${GITHUB_CREDENTIALS_PSW}@github.com/janasajal/cicd-java-app.git main
                """
            }
        }

        stage('ArgoCD Sync DEV') {
            steps {
                script {
                    // Trigger sync via ArgoCD REST API
                    sh """
                        echo "Triggering ArgoCD sync for DEV..."

                        curl -k -s -X POST \
                          -H "Authorization: Bearer ${ARGOCD_TOKEN}" \
                          -H "Content-Type: application/json" \
                          ${ARGOCD_SERVER}/api/v1/applications/cicd-java-app-dev/sync \
                          -d '{}'
                    """

                    // Poll until Synced + Healthy (max 3 minutes)
                    timeout(time: 3, unit: 'MINUTES') {
                        waitUntil {
                            def result = sh(
                                script: """
                                    curl -k -s \
                                      -H "Authorization: Bearer ${ARGOCD_TOKEN}" \
                                      ${ARGOCD_SERVER}/api/v1/applications/cicd-java-app-dev \
                                    | python3 -c "
import sys, json
app = json.load(sys.stdin)
sync   = app['status']['sync']['status']
health = app['status']['health']['status']
print(sync, health)
sys.exit(0 if sync == 'Synced' and health == 'Healthy' else 1)
"
                                """,
                                returnStatus: true
                            )
                            return result == 0
                        }
                    }
                    echo "DEV deployment Synced and Healthy!"
                }
            }
        }

        stage('Manual Approval') {
            steps {
                timeout(time: 5, unit: 'MINUTES') {
                    input message: 'DEV looks good. Approve deployment to PROD?',
                          ok: 'Approve'
                }
            }
        }

        stage('Update PROD Image Tag in Git') {
            steps {
                sh """
                    git config user.email "jenkins@cicd.local"
                    git config user.name "Jenkins CI"

                    sed -i 's|newTag:.*|newTag: "${IMAGE_TAG}"|g' \
                      k8s/overlays/prod/kustomization.yaml

                    git add k8s/overlays/prod/kustomization.yaml
                    git commit -m "CI: update PROD image tag to ${IMAGE_TAG} [skip ci]"

                    git push https://${GITHUB_CREDENTIALS_USR}:${GITHUB_CREDENTIALS_PSW}@github.com/janasajal/cicd-java-app.git main
                """
            }
        }

        stage('ArgoCD Sync PROD') {
            steps {
                script {
                    sh """
                        echo "Triggering ArgoCD sync for PROD..."

                        curl -k -s -X POST \
                          -H "Authorization: Bearer ${ARGOCD_TOKEN}" \
                          -H "Content-Type: application/json" \
                          ${ARGOCD_SERVER}/api/v1/applications/cicd-java-app-prod/sync \
                          -d '{}'
                    """

                    timeout(time: 3, unit: 'MINUTES') {
                        waitUntil {
                            def result = sh(
                                script: """
                                    curl -k -s \
                                      -H "Authorization: Bearer ${ARGOCD_TOKEN}" \
                                      ${ARGOCD_SERVER}/api/v1/applications/cicd-java-app-prod \
                                    | python3 -c "
import sys, json
app = json.load(sys.stdin)
sync   = app['status']['sync']['status']
health = app['status']['health']['status']
print(sync, health)
sys.exit(0 if sync == 'Synced' and health == 'Healthy' else 1)
"
                                """,
                                returnStatus: true
                            )
                            return result == 0
                        }
                    }
                    echo "PROD deployment Synced and Healthy!"
                }
            }
        }
    }

    post {
        success {
            echo 'Pipeline complete! DEV and PROD deployed via ArgoCD.'
        }
        failure {
            echo 'Pipeline failed! Check stage logs above.'
        }
    }
}
```

---

## Troubleshooting Reference

| Error | Cause | Fix |
|---|---|---|
| `mvn: not found` | Maven not in PATH | Add `tools { maven 'maven' }` to Jenkinsfile |
| `docker: not found` | Docker socket not mounted | Mount `/var/run/docker.sock` and `/usr/bin/docker` as hostPath volumes |
| `docker: Permission denied` | hostPath overrides permissions | `kubectl cp /usr/bin/docker jenkins/<pod>:/usr/bin/docker` + `chmod 755` |
| `manifest unknown` openjdk:11-jre-slim | Image removed from DockerHub | Use `eclipse-temurin:11-jre-jammy` |
| `nodePort already allocated` | Fixed port conflict between namespaces | Remove fixed `nodePort`, let K8s auto-assign |
| `fatal: not in a git directory` | Corrupt Jenkins workspace | `rm -rf /var/jenkins_home/workspace/cicd-java-pipeline*` |
| Jenkins pod `Pending` — Insufficient CPU | Resource requests too high | Lower `requests.cpu` to `250m` |
| ArgoCD API returns `401 Unauthorized` | Invalid or expired token | Regenerate token: `argocd account generate-token --account jenkins` |
| ArgoCD app stuck `OutOfSync` | Git path wrong or kustomization invalid | Verify `path` in app manifest matches repo, run `kubectl kustomize k8s/overlays/dev` locally |
| ArgoCD app `Degraded` | Pod failing health check | `kubectl logs -n dev <pod-name>` |
| `[skip ci]` not working | Git provider doesn't support it | Add a webhook filter in Jenkins to ignore commits by `Jenkins CI` |
| Port forward dies | Process killed on pod restart | Re-run all `nohup kubectl port-forward` commands |
| Data lost after pod restart | `emptyDir` storage used | Use PersistentVolumeClaims backed by hostPath PVs |

### Useful Commands

```bash
# ArgoCD application status
kubectl get applications -n argocd
argocd app list
argocd app get cicd-java-app-dev
argocd app get cicd-java-app-prod

# Force sync via CLI
argocd app sync cicd-java-app-dev --insecure --server localhost:8085
argocd app sync cicd-java-app-prod --insecure --server localhost:8085

# Wait for sync to complete
argocd app wait cicd-java-app-prod --sync --health --timeout 120 \
  --insecure --server localhost:8085

# Diff what ArgoCD will change
argocd app diff cicd-java-app-dev

# Rollback to previous version
argocd app rollback cicd-java-app-prod

# Validate kustomize overlays locally
kubectl kustomize k8s/overlays/dev
kubectl kustomize k8s/overlays/prod

# Check deployed app
kubectl get all -n dev
kubectl get all -n prod

# Get ArgoCD admin password
kubectl -n argocd get secret argocd-initial-admin-secret \
  -o jsonpath="{.data.password}" | base64 -d

# Restart Jenkins pod
kubectl rollout restart deployment/jenkins -n jenkins
```

---

## Key Lessons Learned

### 1. ArgoCD API Trigger vs Git Polling
ArgoCD polls Git every 3 minutes by default. For a CI/CD pipeline you don't want to wait — Jenkins triggers an immediate sync via the REST API (`POST /api/v1/applications/<name>/sync`) right after committing the image tag. This combines the speed of push-based delivery with the auditability of GitOps.

### 2. Jenkins Writes to Git, Not to Kubernetes
The key GitOps shift is that Jenkins no longer runs `kubectl apply`. Instead it commits a one-line change (`newTag`) to the Kustomize overlay and lets ArgoCD do the actual deployment. This means the cluster state is always version-controlled and auditable.

### 3. Kustomize Overlays for Environment Separation
Using `base` + `overlays/dev` + `overlays/prod` avoids duplicating YAML across environments. The only difference between DEV and PROD is the `newTag` value and the `namespace` — everything else is inherited from `base`.

### 4. `[skip ci]` Prevents Infinite Loops
When Jenkins pushes an image tag update back to GitHub, GitHub fires another webhook. Without `[skip ci]` in the commit message, this would trigger another Jenkins build infinitely.

### 5. `selfHeal: true` Enforces Git as Single Source of Truth
With `selfHeal` enabled, if anyone manually edits a Kubernetes resource (`kubectl edit deployment`), ArgoCD reverts it within minutes to match Git. This strictly enforces the GitOps principle.

### 6. `prune: true` Keeps the Cluster Clean
When a manifest is deleted from Git, ArgoCD deletes the corresponding Kubernetes resource. Without this, deleted manifests leave orphaned resources accumulating in the cluster over time.

### 7. Separate ArgoCD Account for Jenkins
Never use the `admin` account in Jenkins. Create a dedicated `jenkins` account in ArgoCD with only the permissions it needs (`applications, sync` and `applications, get`). This follows the principle of least privilege.

### 8. ArgoCD Health Polling in Jenkinsfile
After triggering a sync, Jenkins uses `waitUntil` to poll the ArgoCD API until the application reports both `Synced` and `Healthy`. This ensures the pipeline only proceeds (or marks success) once the deployment is genuinely running.

---

## Pipeline Flow Summary

```
┌──────────────────────────────────────────────────────────────────┐
│                    COMPLETE GITOPS PIPELINE                       │
│                                                                   │
│  Stage 1 │ Maven Build & Test                                    │
│          │ mvn clean package → JUnit results published           │
│                                                                   │
│  Stage 2 │ SonarQube Code Scan                                   │
│          │ mvn sonar:sonar → results at :30090                   │
│                                                                   │
│  Stage 3 │ Docker Build & Push                                   │
│          │ docker build → push janasajal/cicd-java-app:N         │
│                                                                   │
│  Stage 4 │ Update DEV Image Tag in Git                           │
│          │ sed newTag → git commit "[skip ci]" → git push        │
│                                                                   │
│  Stage 5 │ ArgoCD Sync DEV                            [API]      │
│          │ POST /api/v1/applications/cicd-java-app-dev/sync      │
│          │ Poll until Synced + Healthy                           │
│                                                                   │
│  Stage 6 │ Manual Approval Gate                        [⏸]      │
│          │ Human reviews DEV → clicks Approve                    │
│                                                                   │
│  Stage 7 │ Update PROD Image Tag in Git                          │
│          │ sed newTag → git commit "[skip ci]" → git push        │
│                                                                   │
│  Stage 8 │ ArgoCD Sync PROD                           [API]      │
│          │ POST /api/v1/applications/cicd-java-app-prod/sync     │
│          │ Poll until Synced + Healthy                           │
└──────────────────────────────────────────────────────────────────┘
```

### Tool Responsibilities

```
Jenkins (CI)                           ArgoCD (CD)
──────────────────────────────         ──────────────────────────────
✅ Compile source code                 ✅ Watch Git for manifest changes
✅ Run unit tests                      ✅ Sync cluster state to Git
✅ Static code analysis (Sonar)        ✅ Self-heal configuration drift
✅ Build Docker image                  ✅ Prune deleted resources
✅ Push to DockerHub                   ✅ Health check deployments
✅ Commit new image tag to Git         ✅ Rollback on sync failure
✅ Call ArgoCD API to trigger sync     ✅ Visual dashboard of app health
✅ Gate production with approval       ✅ Full audit trail in ArgoCD UI
```

### Final Stage Results

| Stage | Tool | Status |
|---|---|---|
| Maven Build & Test | Jenkins + Maven | ✅ |
| SonarQube Code Scan | Jenkins + SonarQube | ✅ |
| Docker Build & Push | Jenkins + Docker | ✅ |
| Update DEV Tag in Git | Jenkins + Git | ✅ |
| Deploy to DEV | ArgoCD (via API trigger) | ✅ |
| Manual Approval | Jenkins input gate | ✅ |
| Update PROD Tag in Git | Jenkins + Git | ✅ |
| Deploy to PROD | ArgoCD (via API trigger) | ✅ |

---

*Document prepared by Sajal Jana — CI/CD Pipeline Lab with ArgoCD GitOps, February 2026*
