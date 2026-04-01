pipeline{
    agent any 
    stages{
        stage('code-pull'){
            steps{
                 git branch: 'main', url: 'https://github.com/Ajay-Nikam-DevOps/Flight-Reservation-Application-using-Devops-Tools.git' 
            }   
        }
        stage('Build'){
            steps{
                sh '''
                    cd frontend
                    npm install
                    npm run build
                '''
            }
        }
        stage('deploy'){
            steps{
                sh '''
                    cd frontend
                    aws s3 sync dist/ s3://cbz-frontend-a67f591d/
                '''
            }
        }
    }
}