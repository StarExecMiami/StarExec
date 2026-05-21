pipeline {
    agent any

    // ---------------------------------------------------------------------------
    // Build options
    // ---------------------------------------------------------------------------
    options {
        timestamps()
        timeout(time: 30, unit: 'MINUTES')
        buildDiscarder(logRotator(
            daysToKeepStr: '30',
            numToKeepStr: '20',
            artifactDaysToKeepStr: '14',
            artifactNumToKeepStr: '5'
        ))
        disableConcurrentBuilds()
        skipDefaultCheckout(true)
    }

    triggers {
        pollSCM('H/5 * * * *')
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
        string(
            name: 'NOTIFICATION_EMAIL',
            defaultValue: '',
            description: 'Email address for build notifications (optional, defaults to dev team email)'
        )
        booleanParam(
            name: 'SKIP_TESTS',
            defaultValue: false,
            description: 'Skip test execution (use only for hotfix builds)'
        )
    }

    // ---------------------------------------------------------------------------
    // Environment variables available to every stage.
    // ---------------------------------------------------------------------------
    environment {
        TAG_SUFFIX      = "${params.IMAGE_TAG_SUFFIX?.trim() ?: env.BUILD_NUMBER}"
        IMAGE_TAG       = "${params.DEPLOY_ENV}-${TAG_SUFFIX}"
        DEPLOY_ENV      = "${params.DEPLOY_ENV}"
        NOTIFICATION_EMAIL = "${params.NOTIFICATION_EMAIL?.trim() ?: 'dev-team@example.com'}"
    }

    // ---------------------------------------------------------------------------
    // Stages
    // ---------------------------------------------------------------------------
    stages {

        stage('Checkout') {
            steps {
                deleteDir()
                checkout scm
                script {
                    def gitSha = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
                    env.GIT_SHA = gitSha

                    // Determine namespace:
                    //   PR builds → ephemeral starexec-pr-{NUMBER}
                    //   Branch builds → starexec-dev
                    if (env.CHANGE_ID) {
                        env.K8S_NAMESPACE   = "starexec-pr-${env.CHANGE_ID}"
                        env.HELM_RELEASE    = "starexec-dev"
                        env.HELM_VALUES     = "charts/starexec/values-dev.yaml"
                        env.APP_URL         = "http://localhost:30081/starexec"
                        env.IS_PR           = "true"
                    } else if (params.DEPLOY_ENV == 'prod') {
                        env.K8S_NAMESPACE   = "starexec"
                        env.HELM_RELEASE    = "starexec"
                        env.HELM_VALUES     = "charts/starexec/values-prod.yaml"
                        env.APP_URL         = "https://quokka.acorn.miami.edu/starexec"
                        env.IS_PR           = "false"
                    } else {
                        env.K8S_NAMESPACE   = "starexec-dev"
                        env.HELM_RELEASE    = "starexec-dev"
                        env.HELM_VALUES     = "charts/starexec/values-dev.yaml"
                        env.APP_URL         = "http://localhost:30081/starexec"
                        env.IS_PR           = "false"
                    }

                    echo "Build context:"
                    echo "  GIT_SHA:       ${gitSha}"
                    echo "  Namespace:     ${K8S_NAMESPACE}"
                    echo "  Is PR:         ${IS_PR}"
                    echo "  Image tag:     ${IMAGE_TAG}"
                }
                sh """
                    echo "=== Build info ==================================="
                    echo "  Job        : ${env.JOB_NAME} #${env.BUILD_NUMBER}"
                    echo "  Git SHA    : ${env.GIT_SHA}"
                    echo "  Namespace  : ${K8S_NAMESPACE}"
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
                echo "Building image with tag: ${env.GIT_SHA}"
                sh """
                    make build IMAGE_TAG=${env.GIT_SHA}
                    podman images | grep "${env.GIT_SHA}" || { echo "ERROR: image not found"; exit 1; }
                """
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
            // Non-secret deployment values (host, port, public URL) live in the
            // Helm values file and are not treated as Jenkins credentials.
            when {
                expression { params.DEPLOY_ENV == 'prod' }
            }
            steps {
                withCredentials([
                    string(credentialsId: 'starExec-db-user',                   variable: 'STAREXEC_DB_USER'),
                    string(credentialsId: 'starExec-db-password',               variable: 'STAREXEC_DB_PASSWORD'),
                ]) {
                    sh '''
                        echo "Validating production configuration..."

                        MISSING=""
                        for VAR in \
                            STAREXEC_DB_PASSWORD \
                            STAREXEC_DB_USER
                        do
                            VAL="$(printenv "$VAR" || true)"
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
        stage('Deploy to Kubernetes') {
        // -----------------------------------------------------------------------
            when {
                expression { params.DEPLOY_ENV != 'prod' }
            }
            steps {
                sh '''
                    echo "Deploying StarExec to Kubernetes..."
                    echo "  Namespace:   ${K8S_NAMESPACE}"
                    echo "  Release:     ${HELM_RELEASE}"
                    echo "  Environment: ${DEPLOY_ENV}"
                    echo "  Git SHA:     ${GIT_SHA}"

                    # Ensure namespace and DB secret exist
                    microk8s kubectl create namespace ${K8S_NAMESPACE} --dry-run=client -o yaml | microk8s kubectl apply -f -

                    microk8s kubectl create secret generic starexec-postgres-credentials \
                        --namespace ${K8S_NAMESPACE} \
                        --from-literal=user=starexec \
                        --from-literal=password=starexec_dev_password \
                        --from-literal=database=starexec \
                        --from-literal=rootPassword=starexec_dev_root_password \
                        --dry-run=client -o yaml | microk8s kubectl apply -f -

                    # Helm deploy with pinned image tag
                    microk8s helm3 upgrade --install ${HELM_RELEASE} \
                        charts/starexec \
                        --namespace ${K8S_NAMESPACE} \
                        --values ${HELM_VALUES} \
                        --set image.tag=${GIT_SHA} \
                        --set image.pullPolicy=IfNotPresent \
                        --timeout 10m --no-hooks 2>&1

                    echo "Waiting for deployment to become available..."
                    microk8s kubectl wait --for=condition=available \
                        --timeout=600s deployment/${HELM_RELEASE} \
                        -n ${K8S_NAMESPACE} || true

                    echo "Running database migrations..."
                    microk8s kubectl exec -n ${K8S_NAMESPACE} \
                        deploy/${HELM_RELEASE} -c app \
                        -- bash /usr/local/bin/migrations.sh 2>&1 || true
                '''
            }
            post {
                success {
                    script {
                        echo "Deployment successful! Application available at: ${APP_URL}"
                    }
                }
                failure {
                    sh '''
                        echo "Deployment failed. Inspect logs with:"
                        echo "  microk8s kubectl -n ${K8S_NAMESPACE} logs deploy/${HELM_RELEASE}"
                        echo "  microk8s kubectl -n ${K8S_NAMESPACE} describe pod"
                    '''
                }
            }
        }

        // -----------------------------------------------------------------------
        stage('Deploy to Production') {
        // -----------------------------------------------------------------------
            when {
                expression { params.DEPLOY_ENV == 'prod' }
            }
            steps {
                input message: "Deploy StarExec to PRODUCTION? (${env.GIT_SHA})", ok: 'Deploy'
                sh '''
                    echo "PRODUCTION DEPLOYMENT — ${GIT_SHA}"
                    BACKUP_DIR="/opt/jenkins/backups/starexec"
                    mkdir -p "${BACKUP_DIR}"
                    BACKUP_FILE="${BACKUP_DIR}/pre-deploy-$(date +%Y%m%d-%H%M%S).sql"
                    microk8s kubectl exec -n ${K8S_NAMESPACE} deploy/${HELM_RELEASE} -c postgres \
                        -- pg_dump -U starexec starexec > "${BACKUP_FILE}" 2>&1 && \
                        echo "Backup: ${BACKUP_FILE}" || echo "Backup skipped"
                    microk8s helm3 upgrade --install ${HELM_RELEASE} \
                        charts/starexec \
                        --namespace ${K8S_NAMESPACE} \
                        --values ${HELM_VALUES} \
                        --set image.tag=${GIT_SHA} \
                        --set image.pullPolicy=IfNotPresent \
                        --atomic --timeout 15m --no-hooks 2>&1
                    microk8s kubectl wait --for=condition=available \
                        --timeout=600s deployment/${HELM_RELEASE} -n ${K8S_NAMESPACE}
                    microk8s kubectl exec -n ${K8S_NAMESPACE} deploy/${HELM_RELEASE} \
                        -c app -- bash /usr/local/bin/migrations.sh 2>&1 || true
                '''
            }
            post {
                success { echo "PRODUCTION DEPLOY SUCCESS: ${APP_URL}" }
                failure { echo "PRODUCTION DEPLOY FAILED — Helm auto-rolled back" }
            }
        }

        stage('Verify Deployment') {
            steps {
                sh '''
                    echo "=== Cluster nodes ==="
                    microk8s kubectl get nodes
                    echo ""
                    echo "=== Pods ==="
                    microk8s kubectl -n ${K8S_NAMESPACE} get pods -o wide
                    echo ""
                    echo "=== Service ==="
                    microk8s kubectl -n ${K8S_NAMESPACE} get svc
                '''
            }
        }

        // -----------------------------------------------------------------------
        stage('Smoke Test') {
        // -----------------------------------------------------------------------
            steps {
                sh '''
                    echo "Waiting for application to become ready..."
                    sleep 15

                    HEALTH_URL="${APP_URL}/secure/index.jsp"
                    echo "Probing: ${HEALTH_URL}"

                    STATUS_CODE=$(curl --silent --output /dev/null \
                                      --write-out "%{http_code}" \
                                      --max-time 30 \
                                      "${HEALTH_URL}" 2>/dev/null) || STATUS_CODE="000"

                    echo "HTTP status: ${STATUS_CODE}"

                    if [ "${STATUS_CODE}" -ne 200 ]; then
                        echo "ERROR: Smoke test failed (expected 200, got ${STATUS_CODE})"
                        echo "=== Pod logs ==="
                        microk8s kubectl -n ${K8S_NAMESPACE} logs deploy/${HELM_RELEASE} --tail=50 || true
                        exit 1
                    fi

                    echo "Smoke test passed."
                '''
            }
        }

    } // end stages

    // ---------------------------------------------------------------------------
    // Post-pipeline notifications
    // ---------------------------------------------------------------------------
    post {
        always {
            script {
                if (env.IS_PR == 'true') {
                    echo "PR ephemeral namespace: ${K8S_NAMESPACE}"
                    echo "To clean up manually:"
                    echo "  microk8s helm3 uninstall ${HELM_RELEASE} -n ${K8S_NAMESPACE}"
                    echo "  microk8s kubectl delete ns ${K8S_NAMESPACE}"
                }
            }
            echo "Pipeline finished — status: ${currentBuild.currentResult}"
        }

        success {
            script {
                def recipient = env.NOTIFICATION_EMAIL ?: 'dev-team@example.com'

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

Application URL: ${APP_URL}

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

        cleanup {
            deleteDir()
        }
    }
}
