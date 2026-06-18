pipeline {
    agent any

    // ---------------------------------------------------------------------------
    // Build options
    // ---------------------------------------------------------------------------
    options {
        timestamps()
        timeout(time: 45, unit: 'MINUTES')
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
            choices: ['dev', 'prod'],
            description: 'Target deployment environment'
        )
        string(
            name: 'NOTIFICATION_EMAIL',
            defaultValue: '',
            description: 'Email address for build notifications (defaults to dev team email)'
        )
        booleanParam(
            name: 'SKIP_TESTS',
            defaultValue: false,
            description: 'Skip test execution (use only for hotfix builds)'
        )
        booleanParam(
            name: 'SKIP_DEPLOY',
            defaultValue: false,
            description: 'Build and test only — skip deployment'
        )
    }

    // ---------------------------------------------------------------------------
    // Environment — resolved once at pipeline start
    // ---------------------------------------------------------------------------
    environment {
        GHCR_REPO          = 'ghcr.io/starexecmiami/starexec'
        JOB_RUNNER_REPO    = 'ghcr.io/starexecmiami/starexec-job-runner'
        DEPLOY_IMAGE_TAG   = 'latest'
        NOTIFICATION_EMAIL = "${params.NOTIFICATION_EMAIL?.trim() ?: 'dev-team@example.com'}"
    }

    // ---------------------------------------------------------------------------
    // Stages
    // ---------------------------------------------------------------------------
    stages {

        // =======================================================================
        stage('Checkout') {
        // =======================================================================
            steps {
                deleteDir()
                checkout scm
                script {
                    def gitSha = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
                    env.GIT_SHA = gitSha

                    // Determine deployment target based on trigger type and parameter.
                    if (env.CHANGE_ID) {
                        // Pull Request → ephemeral namespace
                        env.IS_PR            = 'true'
                        env.K8S_NAMESPACE    = "starexec-pr-${env.CHANGE_ID}"
                        env.HELM_RELEASE     = "starexec-pr-${env.CHANGE_ID}"
                        env.HELM_VALUES      = 'charts/starexec/values-dev.yaml'
                        env.APP_URL          = "http://localhost:30080/starexec"
                        env.HELM_ARGS        = "--set kubernetes.jobNamespace=${env.K8S_NAMESPACE}"
                    } else if (params.DEPLOY_ENV == 'prod') {
                        // Main-branch production
                        env.IS_PR            = 'false'
                        env.K8S_NAMESPACE    = 'starexec'
                        env.HELM_RELEASE     = 'starexec'
                        env.HELM_VALUES      = 'charts/starexec/values-prod.yaml'
                        env.APP_URL          = 'https://quokka.acorn.miami.edu/starexec'
                        env.HELM_ARGS        = ''
                    } else {
                        // dev branch / manual dev
                        env.IS_PR            = 'false'
                        env.K8S_NAMESPACE    = 'starexec-dev'
                        env.HELM_RELEASE     = 'starexec-dev'
                        env.HELM_VALUES      = 'charts/starexec/values-dev.yaml'
                        env.APP_URL          = 'http://localhost:30081/starexec'
                        env.HELM_ARGS        = ''
                    }

                    // Build locally with the git SHA; deploy the published registry tag.
                    env.IMAGE_TAG = env.GIT_SHA

                    echo """
                    ╔══════════════════════════════════════════════════════════╗
                    ║  Build context                                          ║
                    ╠══════════════════════════════════════════════════════════╣
                    ║  Job         : ${env.JOB_NAME} #${env.BUILD_NUMBER}
                    ║  Git SHA     : ${env.GIT_SHA}
                    ║  Build tag   : ${env.IMAGE_TAG}
                    ║  Deploy tag  : ${env.DEPLOY_IMAGE_TAG}
                    ║  Environment : ${params.DEPLOY_ENV}
                    ║  Namespace   : ${env.K8S_NAMESPACE}
                    ║  Helm release: ${env.HELM_RELEASE}
                    ║  Is PR       : ${env.IS_PR}
                    ║  Agent       : ${env.NODE_NAME}
                    ╚══════════════════════════════════════════════════════════╝
                    """
                }
            }
        }


        // =======================================================================
        stage('Build Image') {
        // =======================================================================
            steps {
                script {
                    sh """
                        echo "Building image: ${GHCR_REPO}:${env.IMAGE_TAG}"
                        make build IMAGE_TAG=${env.IMAGE_TAG}
                        podman tag starexec:${env.IMAGE_TAG} ${GHCR_REPO}:${env.IMAGE_TAG}
                        podman images --filter=reference="${GHCR_REPO}:${env.IMAGE_TAG}" \
                            || { echo 'ERROR: image not found after tag'; exit 1; }
                    """
                }
            }
        }


        // =======================================================================
        stage('Test') {
        // =======================================================================
            when {
                expression { !params.SKIP_TESTS }
            }
            steps {
                sh 'make test'
            }
            post {
                always {
                    echo 'Test stage complete'
                }
            }
        }
        // =======================================================================
        stage('Pre-deploy Validation') {
        // =======================================================================
            when {
                expression { params.DEPLOY_ENV == 'prod' && !params.SKIP_DEPLOY }
            }
            steps {
                withCredentials([
                    string(credentialsId: 'starExec-db-user',     variable: 'STAREXEC_DB_USER'),
                    string(credentialsId: 'starExec-db-password', variable: 'STAREXEC_DB_PASSWORD'),
                ]) {
                    sh '''
                        echo "Validating production credentials..."

                        RC=0
                        for VAR in STAREXEC_DB_USER STAREXEC_DB_PASSWORD; do
                            VAL="$(printenv "$VAR" || true)"
                            if [ -z "$VAL" ]; then
                                echo "  MISSING: $VAR"
                                RC=1
                            fi
                        done

                        if [ $RC -ne 0 ]; then
                            echo "ERROR: Required production credentials are missing."
                            exit 1
                        fi

                        echo "✓ All required production credentials present."
                    '''
                }
            }
        }


        // =======================================================================
        stage('Deploy to Kubernetes') {
        // =======================================================================
            when {
                expression { !params.SKIP_DEPLOY }
            }
            steps {
                script {
                    // Production gets a manual gate.
                    if (params.DEPLOY_ENV == 'prod') {
                        input message: "Deploy StarExec to PRODUCTION? (${env.GIT_SHA})", ok: 'Deploy'
                    }
                }

                withCredentials([
                    string(credentialsId: 'starExec-db-user',     variable: 'DB_USER'),
                    string(credentialsId: 'starExec-db-password', variable: 'DB_PASSWORD'),
                ]) {
                    sh """
                        echo "════════════════════════════════════════════════════"
                        echo "  Deploying StarExec"
                        echo "  Namespace   : ${K8S_NAMESPACE}"
                        echo "  Release     : ${HELM_RELEASE}"
                        echo "  Image       : ${GHCR_REPO}:${DEPLOY_IMAGE_TAG}"
                        echo "  Values      : ${HELM_VALUES}"
                        echo "════════════════════════════════════════════════════"

                        # -----------------------------------------------------------------
                        # 1. Ensure namespace exists
                        # -----------------------------------------------------------------
                        microk8s kubectl create namespace ${K8S_NAMESPACE} \\
                            --dry-run=client -o yaml | microk8s kubectl apply -f -

                        # -----------------------------------------------------------------
                        # 2. Upsert DB credentials secret (uses Jenkins-managed creds)
                        # -----------------------------------------------------------------
                        microk8s kubectl create secret generic starexec-postgres-credentials \\
                            --namespace ${K8S_NAMESPACE} \\
                            --from-literal=user="\${DB_USER}" \\
                            --from-literal=password="\${DB_PASSWORD}" \\
                            --from-literal=database=starexec \\
                            --from-literal=rootPassword="\${DB_PASSWORD}" \\
                            --dry-run=client -o yaml | microk8s kubectl apply -f -

                        # -----------------------------------------------------------------
                        # 3. Pre-deploy backup (production only, best-effort)
                        # -----------------------------------------------------------------
                        if [ "${DEPLOY_ENV}" = "prod" ]; then
                            BACKUP_DIR="/starexec/k8s-shared/data/backups"
                            mkdir -p "\${BACKUP_DIR}"
                            BACKUP_FILE="\${BACKUP_DIR}/pre-deploy-\$(date +%Y%m%d-%H%M%S).sql"
                            if microk8s kubectl get deploy/${HELM_RELEASE} -n ${K8S_NAMESPACE} >/dev/null 2>&1; then
                                microk8s kubectl exec -n ${K8S_NAMESPACE} deploy/${HELM_RELEASE} -c postgres \\
                                    -- pg_dump -U starexec starexec > "\${BACKUP_FILE}" 2>&1 && \\
                                    echo "✓ Backup: \${BACKUP_FILE} (\$(wc -c < "\${BACKUP_FILE}") bytes)" || \\
                                    echo "⚠ Backup skipped (postgres not reachable)"
                            else
                                echo "ℹ No existing deployment — backup skipped"
                            fi
                        fi

                        # -----------------------------------------------------------------
                        # 4. Helm deploy (atomic — rolls back on failure)
                        # -----------------------------------------------------------------
                        microk8s helm3 upgrade --install ${HELM_RELEASE} \\
                            charts/starexec \\
                            --namespace ${K8S_NAMESPACE} \\
                            --create-namespace \\
                            --values ${HELM_VALUES} \\
                            --set image.tag=${DEPLOY_IMAGE_TAG} \\
                            --set image.pullPolicy=IfNotPresent \\
                            ${HELM_ARGS} \\
                            --atomic \\
                            --timeout 15m \\
                            --no-hooks 2>&1

                        echo "✓ Helm deploy complete"

                        # -----------------------------------------------------------------
                        # 5. Wait for deployment readiness
                        # -----------------------------------------------------------------
                        microk8s kubectl wait --for=condition=available \\
                            --timeout=600s deployment/${HELM_RELEASE} \\
                            -n ${K8S_NAMESPACE}

                        # -----------------------------------------------------------------
                        # 6. Run database migrations
                        # -----------------------------------------------------------------
                        echo "Running database migrations..."
                        if microk8s kubectl exec -n ${K8S_NAMESPACE} \\
                            deploy/${HELM_RELEASE} -c app \\
                            -- bash /usr/local/bin/migrations.sh 2>&1; then
                            echo "✓ Migrations complete"
                        else
                            echo "⚠ Migrations may have partially applied — check logs"
                        fi
                    """
                }
            }

            post {
                success {
                    echo """
                    ╔══════════════════════════════════════════════════════════╗
                    ║  DEPLOYMENT SUCCESSFUL                                  ║
                    ║  Application: ${APP_URL}                                 ║
                    ║  Image tag:   ${DEPLOY_IMAGE_TAG}                        ║
                    ╚══════════════════════════════════════════════════════════╝
                    """
                }
                failure {
                    echo """
                    ╔══════════════════════════════════════════════════════════╗
                    ║  DEPLOYMENT FAILED                                      ║
                    ║  Helm auto-rolled back to previous release.              ║
                    ║                                                         ║
                    ║  Debug with:                                            ║
                    ║    microk8s kubectl -n ${K8S_NAMESPACE} get pods        ║
                    ║    microk8s kubectl -n ${K8S_NAMESPACE} describe pod    ║
                    ║    microk8s kubectl -n ${K8S_NAMESPACE} logs            ║
                    ║        deploy/${HELM_RELEASE} --tail=100                ║
                    ║    microk8s helm3 history ${HELM_RELEASE}               ║
                    ║        -n ${K8S_NAMESPACE}                              ║
                    ╚══════════════════════════════════════════════════════════╝
                    """
                }
            }
        }


        // =======================================================================
        stage('Verify Deployment') {
        // =======================================================================
            when {
                expression { !params.SKIP_DEPLOY }
            }
            steps {
                sh """
                    echo '=== Cluster nodes ==='
                    microk8s kubectl get nodes

                    echo ''
                    echo '=== Pods (${K8S_NAMESPACE}) ==='
                    microk8s kubectl -n ${K8S_NAMESPACE} get pods -o wide

                    echo ''
                    echo '=== Service ==='
                    microk8s kubectl -n ${K8S_NAMESPACE} get svc

                    echo ''
                    echo '=== Deployment ==='
                    microk8s kubectl -n ${K8S_NAMESPACE} get deploy -o wide
                """
            }
        }


        // =======================================================================
        stage('Smoke Test') {
        // =======================================================================
            when {
                expression { !params.SKIP_DEPLOY }
            }
            steps {
                sh """
                    echo "Waiting for application to stabilize..."
                    sleep 15

                    HEALTH_URL="${APP_URL}/secure/index.jsp"
                    echo "Probing: \${HEALTH_URL}"

                    for i in \$(seq 1 5); do
                        STATUS=\$(curl --silent --output /dev/null \\
                                       --write-out '%{http_code}' \\
                                       --max-time 30 \\
                                       "\${HEALTH_URL}" 2>/dev/null) || STATUS="000"
                        echo "  Attempt \$i: HTTP \${STATUS}"
                        if [ "\${STATUS}" = "200" ]; then
                            echo "✓ Smoke test passed."
                            exit 0
                        fi
                        sleep 10
                    done

                    echo ''
                    echo 'ERROR: Smoke test failed after 5 attempts'
                    echo ''
                    echo '=== App logs (last 50 lines) ==='
                    microk8s kubectl -n ${K8S_NAMESPACE} logs deploy/${HELM_RELEASE} \\
                        -c app --tail=50 2>/dev/null || true
                    echo ''
                    echo '=== Postgres logs (last 20 lines) ==='
                    microk8s kubectl -n ${K8S_NAMESPACE} logs deploy/${HELM_RELEASE} \\
                        -c postgres --tail=20 2>/dev/null || true
                    exit 1
                """
            }
        }

    } // end stages


    // ===========================================================================
    // Post-pipeline
    // ===========================================================================
    post {
        always {
            script {
                if (env.IS_PR == 'true') {
                    echo """
                    ╔══════════════════════════════════════════════════════════╗
                    ║  PR ephemeral environment: ${K8S_NAMESPACE}                ║
                    ║                                                         ║
                    ║  To clean up manually:                                  ║
                    ║    microk8s helm3 uninstall ${HELM_RELEASE}              ║
                    ║      -n ${K8S_NAMESPACE}                                 ║
                    ║    microk8s kubectl delete ns ${K8S_NAMESPACE}           ║
                    ╚══════════════════════════════════════════════════════════╝
                    """
                }
            }
        }

        success {
            script {
                def recipient = env.NOTIFICATION_EMAIL
                mail(
                    to:      recipient,
                    subject: "[SUCCESS] ${env.JOB_NAME} #${env.BUILD_NUMBER} (${params.DEPLOY_ENV})",
                    body:    """\
Pipeline completed successfully.

  Job         : ${env.JOB_NAME}
  Build       : #${env.BUILD_NUMBER}
  Environment : ${params.DEPLOY_ENV}
  Git SHA     : ${env.GIT_SHA}
  Build Image : ${env.GHCR_REPO}:${env.IMAGE_TAG}
  Deploy Image: ${env.GHCR_REPO}:${env.DEPLOY_IMAGE_TAG}
  Duration    : ${currentBuild.durationString}
  Agent       : ${env.NODE_NAME}

Application URL: ${env.APP_URL}

Build log: ${env.BUILD_URL}
""".stripIndent()
                )
            }
        }

        failure {
            script {
                def recipient = env.NOTIFICATION_EMAIL
                mail(
                    to:      recipient,
                    subject: "[FAILURE] ${env.JOB_NAME} #${env.BUILD_NUMBER} (${params.DEPLOY_ENV})",
                    body:    """\
Pipeline failed.

  Job         : ${env.JOB_NAME}
  Build       : #${env.BUILD_NUMBER}
  Environment : ${params.DEPLOY_ENV}
  Git SHA     : ${env.GIT_SHA}
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
