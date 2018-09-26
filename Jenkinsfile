pipeline {
    agent any
    tools {
        maven 'Maven'
        jdk 'Oracle JDK 8'
    }
    stages {
        stage('Build') {
            steps {
                githubNotify description: 'Build is running',  status: 'PENDING'
                sh 'mvn --fail-at-end -B verify'
            }
        }
    }
    post {
        success {
            githubNotify description: 'Build succeeded.', credentialsId: '36ca1390-b7f8-4283-b8fe-85b2ab5f989a', status: 'SUCCESS'
            junit 'shippable/testresults/**/*.xml'
        }
        unstable {
            githubNotify description: 'Build contains test failures.', credentialsId: '36ca1390-b7f8-4283-b8fe-85b2ab5f989a', status: 'ERROR'
            junit 'shippable/testresults/**/*.xml'
        }
        failure {
            githubNotify description: 'Build failed', credentialsId: '36ca1390-b7f8-4283-b8fe-85b2ab5f989a', status: 'FAILURE'
            junit 'shippable/testresults/**/*.xml'
        }
    }
}