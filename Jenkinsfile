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
            githubNotify description: 'Build succeeded.', credentialsId: 'soot-ci', status: 'SUCCESS'
            junit 'shippable/testresults/**/*.xml'
        }
        unstable {
            githubNotify description: 'Build contains test failures.', credentialsId: 'soot-ci', status: 'ERROR'
            junit 'shippable/testresults/**/*.xml'
        }
        failure {
            githubNotify description: 'Build failed', credentialsId: 'soot-ci', status: 'FAILURE'
            junit 'shippable/testresults/**/*.xml'
        }
    }
}