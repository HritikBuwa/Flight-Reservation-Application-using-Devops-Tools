# ✈️ Flight Reservation App — Complete DevOps Deployment Guide

A step-by-step guide to deploy the Flight Reservation app (Backend on EKS + Frontend on S3) using Terraform, Jenkins, SonarQube, Docker, and AWS.

---

## 📁 Repository Structure

| Repo | Purpose |
|------|---------|
| `-Flight-Reservation-Application-using-Devops-Tools-modules or cbz-three-tier-infra` | Terraform — creates AWS infra (EKS, RDS, S3) |
| `Flight-Reservation-Application-using-Devops-Tools` | App code — Backend (Spring Boot) + Frontend (React) + Jenkins pipelines |

---

## 🗺️ Full Deployment Flow

```
Terraform (Infra Repo)
        ↓
  AWS: EKS Cluster + RDS MySQL + S3 Bucket
        ↓
  Jenkins Server (manual EC2 setup)
        ↓
  Backend Pipeline → Build → SonarQube Scan → Docker Push → Deploy to EKS
  Frontend Pipeline → npm Build → Deploy to S3
```

---

## ⚠️ Things YOU Must Change Before Deploying

> Everything marked `🔧 CHANGE THIS` must be updated with your own values.

| What | Where | What to Change |
|------|-------|----------------|
| AWS region | `infra/main.tf` | `us-east-2` → your region |
| Docker image name | `FlightReservationApplication/backend-app.groovy` | `mayurwagh/flight-reservation-pls-18:latest` → `<your-dockerhub-username>/<your-repo>:latest` |
| Docker image name | `FlightReservationApplication/k8s/deployment.yaml` | Same image name as above |
| RDS endpoint | `FlightReservationApplication/application.properties` AND `FlightReservationApplication/src/main/resources/application.properties` | Update with your RDS URL from Terraform output |
| DB username & password | Both `application.properties` files above | Change from defaults |
| Frontend API URL | `frontend/.env` | `VITE_API_URL` → your backend LoadBalancer URL |
| S3 bucket name | `frontend/frontend-pipeline.groovy` | `cblkdfsfdsc-front12end-project-bux` → your S3 bucket name |
| Ingress host | `FlightReservationApplication/k8s/ingress.yaml` | `reservation.oncdecb24erp.shop` → your domain (or remove ingress) |
| GitHub repo URL | Both Jenkinsfiles | Update to your forked repo URL |

---

## PART 1 — Infrastructure Setup (Terraform)

### Step 1 — Clone Infra Repo & Configure AWS Region

```bash
git clone <infra-repo-url>
cd cbz-three-tier-infra
```

Open `main.tf` and set your AWS region:

```hcl
# 🔧 CHANGE THIS — set your preferred region
provider "aws" {
  region = "us-east-2"
}
```

> **AWS Auth:** Make sure AWS CLI is configured on your local machine (`aws configure`) before running Terraform.

---

### Step 2 — Deploy Infrastructure

```bash
terraform init
terraform plan
terraform apply
```

This creates:
- **EKS Cluster** — named `cbz-cluster` with 2x `t3.medium` nodes
- **RDS MySQL** — your application database
- **S3 Bucket** — for frontend static files

After apply completes, **save the outputs** — you'll need the RDS endpoint and S3 bucket name.

```bash
terraform output
```

---

## PART 2 — Jenkins Server Setup

> Jenkins is set up manually on an EC2 instance (not provisioned by the infra Terraform). Launch a separate Ubuntu EC2 (t2.medium or larger) for Jenkins.

### Step 3 — SSH into Jenkins EC2

```bash
ssh ubuntu@<jenkins-ec2-public-ip>
```

---

### Step 4 — Install Jenkins

> ℹ️ This uses the **official Jenkins LTS (Long-Term Support) install method** for Ubuntu — recommended for production stability over weekly releases. It adds the Jenkins stable apt repository and installs Jenkins via `apt`.

```bash
sudo apt update -y
sudo apt install openjdk-17-jdk -y

sudo wget -O /etc/apt/keyrings/jenkins-keyring.asc \
  https://pkg.jenkins.io/debian-stable/jenkins.io-2023.key

echo "deb [signed-by=/etc/apt/keyrings/jenkins-keyring.asc] \
  https://pkg.jenkins.io/debian-stable binary/" | sudo tee \
  /etc/apt/sources.list.d/jenkins.list > /dev/null

sudo apt update -y
sudo apt install jenkins -y

sudo systemctl enable jenkins
sudo systemctl start jenkins
```

---

### Step 5 — Install Required Tools on Jenkins EC2

```bash
# Git and Maven
sudo apt install git maven -y

# Docker (official method)
sudo apt-get install ca-certificates curl -y
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] \
  https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "${VERSION_CODENAME}") stable" | \
  sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
sudo apt-get update
sudo apt-get install docker-ce docker-ce-cli containerd.io -y

# Add users to docker group
sudo usermod -aG docker ubuntu
sudo usermod -aG docker jenkins
sudo systemctl restart docker

# Node.js (for frontend pipeline)
curl -fsSL https://deb.nodesource.com/setup_18.x | sudo -E bash -
sudo apt install nodejs -y
```

---

### Step 6 — Open Security Group Ports

In AWS Console → EC2 → Security Groups, allow inbound:

| Port | Service |
|------|---------|
| `22` | SSH |
| `8080` | Jenkins UI |
| `9000` | SonarQube UI (if on same server) |

---

### Step 7 — Access & Unlock Jenkins

Open in browser:
```
http://<JENKINS-EC2-IP>:8080
```

Get the initial admin password:
```bash
sudo cat /var/lib/jenkins/secrets/initialAdminPassword
```

Complete the setup wizard and install **Suggested Plugins**.

---

### Step 8 — Install Additional Jenkins Plugins

Go to **Manage Jenkins → Plugins → Available** and install:

- SonarQube Scanner
- Pipeline Stage View

Restart Jenkins after installation.

---

## PART 3 — SonarQube Setup

### Step 9 — Install SonarQube (on separate EC2 or same server)

```bash
sudo apt update
sudo apt install openjdk-17-jdk postgresql -y
sudo systemctl start postgresql

# Create DB and user
sudo -u postgres psql
```

Inside psql:
```sql
CREATE USER sonar WITH PASSWORD 'sonar123';
CREATE DATABASE sonarqube OWNER sonar;
GRANT ALL PRIVILEGES ON DATABASE sonarqube TO sonar;
\q
```

```bash
# System config required by SonarQube
sudo sysctl -w vm.max_map_count=524288
sudo sysctl -w fs.file-max=131072

# Download and install SonarQube
wget https://binaries.sonarsource.com/Distribution/sonarqube/sonarqube-25.5.0.107428.zip
sudo apt install unzip -y
unzip sonarqube-25.5.0.107428.zip
sudo mv sonarqube-25.5.0.107428 /opt/sonar

# Configure DB connection
sudo vim /opt/sonar/conf/sonar.properties
```

Add/uncomment these lines in `sonar.properties`:
```properties
sonar.jdbc.username=sonar
sonar.jdbc.password=sonar123
sonar.jdbc.url=jdbc:postgresql://localhost/sonarqube
```

```bash
# Run as non-root user
sudo useradd sonar -m
sudo chown sonar:sonar -R /opt/sonar
sudo -u sonar /opt/sonar/bin/linux-x86-64/sonar.sh start
```

---

### Step 10 — Access SonarQube & Create Project

Open in browser:
```
http://<SONARQUBE-EC2-IP>:9000
```

Default login: `admin` / `admin` (you'll be prompted to change it).

**Create project and token:**
1. Click **Create Project → Manual**
2. Set **Project Key**: `Flight-Reservation` (must match exactly what's in the pipeline — case-sensitive)
3. Click **Generate Token** → name it `jenkins-token` → copy the token value
4. Click **Continue → Maven** (select project type)

---

### Step 11 — Add SonarQube Token to Jenkins

In Jenkins, go to **Manage Jenkins → Credentials → System → Global → Add Credentials**:

| Field | Value |
|-------|-------|
| Kind | Secret text |
| Secret | `<the token you copied from SonarQube>` |
| ID | `sonar-token` ← **must be exactly this** |

Then go to **Manage Jenkins → System → SonarQube Servers**:

| Field | Value |
|-------|-------|
| Name | `sonar` ← **must be exactly this** |
| Server URL | `http://<sonarqube-ip>:9000` |
| Token | Select `sonar-token` credential |

---

## PART 4 — Configure Application Files

### Step 12 — Update `application.properties`

> ⚠️ **Security Warning:** The repo currently has a real RDS endpoint, username, and password committed in plaintext. Replace ALL of these with your own values before pushing.

> 📝 **Two files to update:** There are **two copies** of `application.properties` — update **both**:
> - `FlightReservationApplication/application.properties` ← outside (root-level copy)
> - `FlightReservationApplication/src/main/resources/application.properties` ← inside (used at build/runtime)

Apply the **same changes** to **both files**:

```properties
# 🔧 CHANGE THIS — use your RDS endpoint from terraform output
spring.datasource.url=jdbc:mysql://<YOUR-RDS-ENDPOINT>:3306/flightdb?createDatabaseIfNotExist=true

# 🔧 CHANGE THIS
spring.datasource.username=<YOUR-DB-USERNAME>

# 🔧 CHANGE THIS
spring.datasource.password=<YOUR-DB-PASSWORD>

# 🔧 CHANGE THIS — use your frontend URL after S3/CloudFront setup
frontend.url=http://<YOUR-FRONTEND-URL>

# Keep these as-is
spring.jpa.hibernate.ddl-auto=update
server.address=0.0.0.0
```

---

### Step 13 — Update Backend Pipeline (Docker Image Name + Java Version + SonarQube Key)

Edit file: `FlightReservationApplication/backend-app.groovy`

**1. Change Java version from 21 to 17 on the Jenkins server:**

```bash
sudo apt update
sudo apt install openjdk-17-jdk -y

sudo update-alternatives --config java
# Select the number corresponding to /usr/lib/jvm/java-17-openjdk-amd64

java -version
# Should output: openjdk version "17..."

sudo systemctl restart jenkins
```

**2. Set the correct SonarQube project key** (must match exactly what you created in Step 10):
```groovy
stage('SonarQube-Analysis'){
    steps{
        withSonarQubeEnv('sonar'){
            sh '''
                cd FlightReservationApplication
                mvn sonar:sonar -Dsonar.projectKey=Flight-Reservation
            '''
        }
    }
}
```

**3. Update the Docker image name:**
```groovy
stage('Docker-Build'){
    steps{
        sh '''
            cd FlightReservationApplication
            # 🔧 CHANGE THIS — replace with your DockerHub username/repo
            docker build -t <your-dockerhub-username>/<your-repo>:latest .
            docker push <your-dockerhub-username>/<your-repo>:latest
            docker rmi <your-dockerhub-username>/<your-repo>:latest
        '''
    }
}
```

Also update the repo URL at the top:
```groovy
git branch: 'main', url: 'https://github.com/<your-username>/flight-reservation-app.git'
```

---

### Step 14 — Update K8s Deployment Image

Edit file: `FlightReservationApplication/k8s/deployment.yaml`

```yaml
containers:
- name: flight-reservation-app
  # 🔧 CHANGE THIS — must match the image name in your pipeline
  image: <your-dockerhub-username>/<your-repo>:latest
```

---

### Step 15 — Update Frontend `.env`

Edit file: `frontend/.env`

```env
# 🔧 CHANGE THIS — your backend LoadBalancer URL (from kubectl get svc after deploy)
VITE_API_URL=http://<BACKEND-LOADBALANCER-URL>
```

> You'll get the LoadBalancer URL after the backend is deployed in Step 23. Come back and update this before running the frontend pipeline.

---

### Step 16 — Update Frontend Pipeline (S3 Bucket Name)

Edit file: `frontend/frontend-pipeline.groovy`

```groovy
stage('deploy'){
    steps{
        sh '''
            cd frontend
            # 🔧 CHANGE THIS — use your S3 bucket name from terraform output
            aws s3 sync dist/ s3://<YOUR-S3-BUCKET-NAME>/
        '''
    }
}

// Also update the repo URL:
git branch: 'main', url: 'https://github.com/<your-username>/flight-reservation-app.git'
```

---

### Step 17 — Push All Changes to GitHub

```bash
git add .
git commit -m "update configs for deployment"
git push origin main
```

---

## PART 5 — Jenkins Credentials & Cluster Setup

### Step 18 — Add Docker Credentials to Jenkins

In Jenkins → **Manage Jenkins → Credentials → System → Global → Add Credentials**:

| Field | Value |
|-------|-------|
| Kind | Username with password |
| Username | Your DockerHub username |
| Password | Your DockerHub password or access token |
| ID | `docker-cred` |

---

### Step 19 — Docker Login as Jenkins User

```bash
sudo su - jenkins
docker login
# Enter your DockerHub username and password when prompted

# Fix permissions
chown -R jenkins:jenkins ~/.docker/
exit
```

---

### Step 20 — Install kubectl & AWS CLI

```bash
# kubectl
curl -LO "https://dl.k8s.io/release/$(curl -L -s https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl"
sudo install -o root -g root -m 0755 kubectl /usr/local/bin/kubectl
kubectl version --client

# AWS CLI
sudo apt install unzip -y
curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "awscliv2.zip"
unzip awscliv2.zip
sudo ./aws/install
```

---

### Step 21 — Configure AWS CLI & Connect to EKS

```bash
aws configure
# Enter: Access Key, Secret Key, Region (must match infra region), output format (json)

# 🔧 CHANGE THIS — replace region with your actual region
aws eks update-kubeconfig --name cbz-cluster --region us-east-2
```

Verify connection:
```bash
kubectl get nodes
# You should see 2 nodes in Ready state
```

---

### Step 22 — Copy AWS & Kube Config to Jenkins User

```bash
sudo cp -rf ~/.aws /var/lib/jenkins/
sudo cp -rf ~/.kube /var/lib/jenkins/

sudo chown -R jenkins:jenkins /var/lib/jenkins/.aws
sudo chown -R jenkins:jenkins /var/lib/jenkins/.kube
```

---

### Step 23 — Restart Jenkins

```bash
sudo systemctl restart jenkins
```

---

## PART 6 — Create Jenkins Pipeline Jobs

### Step 24 — Backend Pipeline Job

1. Go to Jenkins Dashboard → **New Item**
2. Name: `flight-backend-pipeline` → Select **Pipeline** → OK
3. Configure:

| Field | Value |
|-------|-------|
| Definition | Pipeline script from SCM |
| SCM | Git |
| Repository URL | `https://github.com/<your-username>/flight-reservation-app.git` |
| Branch | `main` |
| Script Path | `FlightReservationApplication/backend-app.groovy` |

4. Click **Save**

---

### Step 25 — Frontend Pipeline Job

1. Go to Jenkins Dashboard → **New Item**
2. Name: `flight-frontend-pipeline` → Select **Pipeline** → OK
3. Configure:

| Field | Value |
|-------|-------|
| Definition | Pipeline script from SCM |
| SCM | Git |
| Repository URL | `https://github.com/<your-username>/flight-reservation-app.git` |
| Branch | `main` |
| Script Path | `frontend/frontend-pipeline.groovy` |

4. Click **Save**

---

## PART 7 — Deploy

### Step 26 — Run Backend Pipeline

1. Open `flight-backend-pipeline` → Click **Build Now**
2. Watch the stages: Code Pull → Build → QA Test (SonarQube) → Docker Build → Deploy

**After Deploy completes**, get your backend LoadBalancer URL:
```bash
kubectl get svc -n flight-reservation
# Copy the EXTERNAL-IP of flight-reservation-service
```

---

### Step 27 — Handle Namespace Error (If Any)

> If the Deploy stage fails with a namespace error, don't worry — the namespace just needs a moment to be created.

1. Wait 30 seconds
2. In the pipeline, click **Restart from Stage**
3. Select **Deploy** → Click **Run**

---

### Step 28 — Update Frontend URL & Run Frontend Pipeline

Now that the backend is deployed:

1. Copy the `EXTERNAL-IP` from Step 26
2. Update `frontend/.env`:
   ```env
   VITE_API_URL=http://<BACKEND-LOADBALANCER-EXTERNAL-IP>
   ```
3. Push the change:
   ```bash
   git add .
   git commit -m "update frontend API URL"
   git push origin main
   ```
4. In Jenkins, open `flight-frontend-pipeline` → Click **Build Now**

---

### Step 29 — Verify Full Deployment

```bash
# Check all pods are running
kubectl get pods -n flight-reservation

# Check services
kubectl get svc -n flight-reservation

# Check HPA (auto-scaling)
kubectl get hpa -n flight-reservation
```

---

## ✅ Done!

| Component | Access |
|-----------|--------|
| **Backend API** | `http://<LoadBalancer-EXTERNAL-IP>` |
| **Frontend** | `http://<S3-bucket-website-endpoint>` |
| **Jenkins** | `http://<jenkins-ip>:8080` |
| **SonarQube** | `http://<sonarqube-ip>:9000` |

---

## 📌 File Reference

| File | Purpose |
|------|---------|
| `infra/main.tf` | AWS region + module config |
| `FlightReservationApplication/backend-app.groovy` | Backend Jenkins pipeline |
| `FlightReservationApplication/src/main/resources/application.properties` | DB + server config |
| `FlightReservationApplication/k8s/deployment.yaml` | Docker image for K8s |
| `FlightReservationApplication/k8s/service.yaml` | LoadBalancer service |
| `FlightReservationApplication/k8s/ns.yaml` | Namespace: `flight-reservation` |
| `FlightReservationApplication/k8s/ingress.yaml` | Ingress (update or remove host) |
| `FlightReservationApplication/k8s/hpa.yaml` | Horizontal Pod Autoscaler |
| `frontend/.env` | Frontend API URL config |
| `frontend/frontend-pipeline.groovy` | Frontend Jenkins pipeline |
