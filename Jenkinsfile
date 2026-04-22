pipeline {
    agent any

    // ---------------------------------------------------------------------------
    // Build options
    // ---------------------------------------------------------------------------
    options {
        timestamps()
        timeout(time: 30, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '20', artifactNumToKeepStr: '5'))
        disableConcurrentBuilds()
    }

    // ---------------------------------------------------------------------------
    // Parameters
    // ---------------------------------------------------------------------------
    parameters {
        choice(
            name: 'DEPLOY_ENV',
            choices: ['dev', 'ci', 'prod'],
            description: 'Target deployment environment'
        )
        string(
            name: 'IMAGE_TAG_SUFFIX',
            defaultValue: '',
            description: 'Image tag suffix (leave blank to use the build number)'
        )
        booleanParam(
            name: 'SKIP_TESTS',
            defaultValue: false,
            description: 'Skip test execution (use only for hotfix builds)'
        )
    }

    // ---------------------------------------------------------------------------
    // Environment variables available to every stage.
    // Secrets that are only required in production are loaded with withCredentials
    // inside the relevant stages so that the pipeline does not fail on dev/ci
    // builds where those credential IDs may not exist.
    // ---------------------------------------------------------------------------
    environment {
        TAG_SUFFIX      = "${params.IMAGE_TAG_SUFFIX?.trim() ?: env.BUILD_NUMBER}"
        IMAGE_TAG       = "${params.DEPLOY_ENV}-${TAG_SUFFIX}"
        DEPLOY_ENV      = "${params.DEPLOY_ENV}"
        IS_PROD         = "${params.DEPLOY_ENV == 'prod'}"

        // The DB password credential is required in every environment.
        STAREXEC_DB_PASSWORD = credentials('starExec-db-password')
    }

    // ---------------------------------------------------------------------------
    // Stages
    // ---------------------------------------------------------------------------
    stages {

        stage('Checkout') {
            steps {
                checkout scm
                sh """
                    echo "=== Build info ==================================="
                    echo "  Job        : ${env.JOB_NAME} #${env.BUILD_NUMBER}"
                    echo "  Image tag  : ${IMAGE_TAG}"
                    echo "  Environment: ${DEPLOY_ENV}"
                    echo "  Agent      : \$(hostname)"
                    echo "================================================="
                """
            }
        }

        // -----------------------------------------------------------------------
        stage('Build Image') {
        // -----------------------------------------------------------------------
            steps {
                sh 'make build'
                sh 'podman images | grep starexec || { echo "ERROR: starexec image not found after build"; exit 1; }'
            }
        }

        // -----------------------------------------------------------------------
        stage('Test') {
        // -----------------------------------------------------------------------
            when {
                expression { !params.SKIP_TESTS }
            }
            steps {
                sh 'make test'
            }
            post {
                always {
                    // Uncomment when test reports are available:
                    // junit allowEmptyResults: true, testResults: '**/target/surefire-reports/*.xml'
                    echo 'Test stage complete'
                }
            }
        }

        // -----------------------------------------------------------------------
        stage('Pre-deploy Validation') {
        // -----------------------------------------------------------------------
            // Validate that every required production secret is resolvable before
            // we attempt a deployment that would fail halfway through.
            when {
                allOf {
                    branch 'containerised'
                    expression { params.DEPLOY_ENV == 'prod' }
                }
            }
            steps {
                withCredentials([
                    string(credentialsId: 'starExec-db-user',                   variable: 'STAREXEC_DB_USER'),
                    string(credentialsId: 'starExec-db-host',                   variable: 'STAREXEC_DB_HOST'),
                    string(credentialsId: 'starExec-db-port',                   variable: 'STAREXEC_DB_PORT'),
                    string(credentialsId: 'starExec-db-name',                   variable: 'STAREXEC_DB_NAME'),
                    string(credentialsId: 'starExec-web-address',               variable: 'STAREXEC_WEB_ADDRESS'),
                    string(credentialsId: 'starExec-proxy-address',             variable: 'STAREXEC_PROXY_ADDRESS'),
                    string(credentialsId: 'starExec-proxy-port',                variable: 'STAREXEC_PROXY_PORT'),
                    string(credentialsId: 'starExec-email-smtp',                variable: 'STAREXEC_EMAIL_SMTP'),
                    string(credentialsId: 'starExec-email-port',                variable: 'STAREXEC_EMAIL_PORT'),
                    string(credentialsId: 'starExec-email-user',                variable: 'STAREXEC_EMAIL_USER'),
                    string(credentialsId: 'starExec-email-password',            variable: 'STAREXEC_EMAIL_PASSWORD'),
                    string(credentialsId: 'starExec-container-host-data-path',  variable: 'STAREXEC_CONTAINER_HOST_DATA_PATH'),
                ]) {
                    sh '''
                        echo "Validating production configuration..."

                        MISSING=""
                        for VAR in \
                            STAREXEC_DB_PASSWORD \
                            STAREXEC_DB_USER \
                            STAREXEC_DB_HOST \
                            STAREXEC_DB_PORT \
                            STAREXEC_DB_NAME \
                            STAREXEC_WEB_ADDRESS \
                            STAREXEC_PROXY_ADDRESS \
                            STAREXEC_PROXY_PORT \
                            STAREXEC_EMAIL_SMTP \
                            STAREXEC_EMAIL_PORT \
                            STAREXEC_EMAIL_USER \
                            STAREXEC_EMAIL_PASSWORD \
                            STAREXEC_CONTAINER_HOST_DATA_PATH
                        do
                            eval "VAL=\$$VAR"
                            if [ -z "$VAL" ]; then
                                echo "  MISSING: $VAR"
                                MISSING="$MISSING $VAR"
                            fi
                        done

                        if [ -n "$MISSING" ]; then
                            echo "ERROR: The following required variables are unset:$MISSING"
                            exit 1
                        fi

                        echo "All required production credentials are present."
                    '''
                }
            }
        }

        // -----------------------------------------------------------------------
        stage('Deploy') {
        // -----------------------------------------------------------------------
            when {
                // Only deploy from the main branch to prevent accidental promotions.
                branch 'containerised'
            }
            steps {
                // Production deployments additionally need the full set of secrets.
                script {
                    if (params.DEPLOY_ENV == 'prod') {
                        withCredentials([
                            string(credentialsId: 'starExec-db-user',                   variable: 'STAREXEC_DB_USER'),
                            string(credentialsId: 'starExec-db-host',                   variable: 'STAREXEC_DB_HOST'),
                            string(credentialsId: 'starExec-db-port',                   variable: 'STAREXEC_DB_PORT'),
                            string(credentialsId: 'starExec-db-name',                   variable: 'STAREXEC_DB_NAME'),
                            string(credentialsId: 'starExec-web-address',               variable: 'STAREXEC_WEB_ADDRESS'),
                            string(credentialsId: 'starExec-proxy-address',             variable: 'STAREXEC_PROXY_ADDRESS'),
                            string(credentialsId: 'starExec-proxy-port',                variable: 'STAREXEC_PROXY_PORT'),
                            string(credentialsId: 'starExec-email-smtp',                variable: 'STAREXEC_EMAIL_SMTP'),
                            string(credentialsId: 'starExec-email-port',                variable: 'STAREXEC_EMAIL_PORT'),
                            string(credentialsId: 'starExec-email-user',                variable: 'STAREXEC_EMAIL_USER'),
                            string(credentialsId: 'starExec-email-password',            variable: 'STAREXEC_EMAIL_PASSWORD'),
                            string(credentialsId: 'starExec-container-host-data-path',  variable: 'STAREXEC_CONTAINER_HOST_DATA_PATH'),
                        ]) {
                            sh '''
                                make deploy-podman \
                                    ENV="${DEPLOY_ENV}" \
                                    IMAGE_TAG="${IMAGE_TAG}" \
                                    FORCE=1
                            '''
                        }
                    } else {
                        sh '''
                            make deploy-podman \
                                ENV="${DEPLOY_ENV}" \
                                IMAGE_TAG="${IMAGE_TAG}" \
                                FORCE=1
                        '''
                    }
                }
            }
            post {
                success {
                    script {
                        def url = (params.DEPLOY_ENV == 'prod')
                            ? "http://\${STAREXEC_WEB_ADDRESS}:\${STAREXEC_PROXY_PORT:-80}/starexec"
                            : 'http://localhost:7827/starexec'
                        echo "Deployment successful! Application available at: ${url}"
                    }
                }
                failure {
                    sh 'echo "Deployment failed. Inspect logs with: make logs-app"'
                }
            }
        }

        // -----------------------------------------------------------------------
        stage('Smoke Test') {
        // -----------------------------------------------------------------------
            when {
                allOf {
                    branch 'containerised'
                    expression { params.DEPLOY_ENV == 'prod' }
                }
            }
            steps {
                withCredentials([
                    string(credentialsId: 'starExec-web-address', variable: 'STAREXEC_WEB_ADDRESS'),
                    string(credentialsId: 'starExec-proxy-port',  variable: 'STAREXEC_PROXY_PORT'),
                ]) {
                    sh '''
                        echo "Waiting for application to become ready..."
                        sleep 30

                        HEALTH_URL="http://${STAREXEC_WEB_ADDRESS}:${STAREXEC_PROXY_PORT:-80}/starexec/login"
                        echo "Probing: ${HEALTH_URL}"

                        STATUS_CODE=$(curl --silent --output /dev/null \
                                          --write-out "%{http_code}" \
                                          --max-time 10 \
                                          "${HEALTH_URL}" || echo "000")

                        echo "HTTP status: ${STATUS_CODE}"

                        if [ "${STATUS_CODE}" -ne 200 ]; then
                            echo "ERROR: Smoke test failed (expected 200, got ${STATUS_CODE})"
                            echo "Inspect application logs with: make logs-app"
                            exit 1
                        fi

                        echo "Smoke test passed."
                    '''
                }
            }
        }

    } // end stages

    // ---------------------------------------------------------------------------
    // Post-pipeline notifications
    // ---------------------------------------------------------------------------
    post {
        always {
            // Uncomment when build artefacts are produced:
            // archiveArtifacts artifacts: '**/target/*.xml', allowEmptyArchive: true, fingerprint: true
            echo "Pipeline finished — status: ${currentBuild.currentResult}"
        }

        success {
            script {
                def recipient = env.NOTIFICATION_EMAIL ?: 'dev-team@example.com'
                def appUrl = (params.DEPLOY_ENV == 'prod')
                    ? "http://\${STAREXEC_WEB_ADDRESS}:\${STAREXEC_PROXY_PORT:-80}/starexec"
                    : 'http://localhost:7827/starexec'

                mail(
                    to:      recipient,
                    subject: "[SUCCESS] ${env.JOB_NAME} #${env.BUILD_NUMBER} (${DEPLOY_ENV})",
                    body:    """\
Pipeline completed successfully.

  Job         : ${env.JOB_NAME}
  Build       : #${env.BUILD_NUMBER}
  Environment : ${DEPLOY_ENV}
  Image       : ${IMAGE_TAG}
  Duration    : ${currentBuild.durationString}
  Agent       : ${env.NODE_NAME}

Application URL: ${appUrl}

Build log: ${env.BUILD_URL}
""".stripIndent()
                )
            }
        }

        failure {
            script {
                def recipient = env.NOTIFICATION_EMAIL ?: 'dev-team@example.com'

                mail(
                    to:      recipient,
                    subject: "[FAILURE] ${env.JOB_NAME} #${env.BUILD_NUMBER} (${DEPLOY_ENV})",
                    body:    """\
Pipeline failed.

  Job         : ${env.JOB_NAME}
  Build       : #${env.BUILD_NUMBER}
  Environment : ${DEPLOY_ENV}
  Failed stage: ${env.STAGE_NAME ?: 'unknown'}
  Duration    : ${currentBuild.durationString}

Build log: ${env.BUILD_URL}consoleFull
""".stripIndent()
                )
            }
        }
    }
}
