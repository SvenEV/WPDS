pipeline {
    agent any
    tools {
        maven 'Maven'
        jdk 'Oracle JDK 8'
    }
    stages {
        stage('Build') {
            steps {
                sh 'mvn -Dmaven.test.failure.ignore=true -B verify'
            }
        }
    }
}