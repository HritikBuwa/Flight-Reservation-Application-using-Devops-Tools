pipeline {
    agent any 
    stages {
        stage('Code-Pull'){
            steps{
                git branch: 'main', url: 'https://github.com/HritikBuwa/Flight-Reservation-Application-using-Devops-Tools.git'    
            }
        }
        stage('Code-Build'){
            steps{
                sh '''
                    cd FlightReservationApplication
                    mvn clean package 
                '''
            }
        }
        stage('QA-Test'){
            steps{
                withSonarQubeEnv(installationName: 'sonar', credentialsId: 'sonar-token') {
                 sh '''
                    cd FlightReservationApplication
                    mvn sonar:sonar -Dsonar.projectKey=flight-reservation
                 '''
                }
            }
        }
        stage('Docker-Build'){
            steps{
                sh '''
                    cd FlightReservationApplication
                    docker build -t hritikbuwa/flightreservationapplication:latest . 
                    docker push hritikbuwa/flightreservationapplication:latest 
                    docker rmi hritikbuwa/flightreservationapplication:latest 
                '''
            }
        }
        stage('Deploy'){
            steps{
                sh '''
                    cd FlightReservationApplication
                    kubectl apply -f k8s/
                '''
            }
        }
    }
}
