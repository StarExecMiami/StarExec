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
                    def gitSha = sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
                    env.GIT_SHA = gitSha

                    // Determine deployment target based on trigger type and parameter.
                    if (env.CHANGE_ID) {
                        // Pull Request → ephemeral namespace
                        env.IS_PR            = 'true'
                        env.K8S_NAMESPACE    = "starexec-pr-${env.CHANGE_ID}"
                        env.HELM_RELEASE     = "starexec-pr-${env.CHANGE_ID}"
                        env.HELM_VALUES      = 'charts/starexec/values-dev.yaml'
                        env.APP_URL          = "http://localhost:30081/starexec"
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

                    // GitHub Actions publishes the app image. Jenkins deploys only
                    // the artifact validated by a successful workflow for this SHA.
                    env.PUBLISHED_IMAGE_TAG = "sha-${env.GIT_SHA}"
                    env.PUBLISHED_IMAGE = "${GHCR_REPO}:${env.PUBLISHED_IMAGE_TAG}"

                    echo """
                    ╔══════════════════════════════════════════════════════════╗
                    ║  Build context                                          ║
                    ╠══════════════════════════════════════════════════════════╣
                    ║  Job         : ${env.JOB_NAME} #${env.BUILD_NUMBER}
                    ║  Git SHA     : ${env.GIT_SHA}
                    ║  Published tag: ${env.PUBLISHED_IMAGE_TAG}
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
        stage('Resolve Published Image') {
        // =======================================================================
            when {
                expression { env.IS_PR != 'true' }
            }
            steps {
                script {
                    env.DEPLOY_IMAGE_DIGEST = sh(
                        script: """
                            set -eu

                            command -v curl >/dev/null
                            command -v jq >/dev/null
                            command -v podman >/dev/null

                            WORKFLOW_RUNS_URL="https://api.github.com/repos/StarExecMiami/StarExec/actions/workflows/container-publish.yml/runs?head_sha=${env.GIT_SHA}&status=completed&per_page=100"
                            RUN_ID=""
                            for attempt in \$(seq 1 30); do
                                if ! WORKFLOW_RUNS="\$(curl --fail --silent --show-error --retry 3 --retry-delay 2 --retry-all-errors \\
                                    -H 'Accept: application/vnd.github+json' "\${WORKFLOW_RUNS_URL}")"; then
                                    echo "GitHub workflow lookup failed (attempt \${attempt}/30); retrying..." >&2
                                    sleep 30
                                    continue
                                fi
                                RUN_ID="\$(printf '%s' "\${WORKFLOW_RUNS}" | jq -r --arg sha '${env.GIT_SHA}' \\
                                    '[.workflow_runs[] | select(.head_sha == \$sha and .conclusion == "success")] | sort_by(.updated_at) | last | .id // empty')"
                                if [ -n "\${RUN_ID}" ]; then
                                    break
                                fi

                                echo "Waiting for successful Container Publish workflow (attempt \${attempt}/30)" >&2
                                sleep 30
                            done

                            if [ -z "\${RUN_ID}" ]; then
                                echo "ERROR: no successful Container Publish workflow completed within 15 minutes for ${env.GIT_SHA}" >&2
                                exit 1
                            fi

                            echo "Verified Container Publish run \${RUN_ID} for ${env.GIT_SHA}" >&2
                            podman pull "${env.PUBLISHED_IMAGE}" >&2

                            REPOSITORY_DIGEST="\$(podman image inspect --format '{{index .RepoDigests 0}}' "${env.PUBLISHED_IMAGE}")"
                            DEPLOY_IMAGE_DIGEST="\${REPOSITORY_DIGEST#*@}"
                            case "\${DEPLOY_IMAGE_DIGEST}" in
                                sha256:*) printf '%s' "\${DEPLOY_IMAGE_DIGEST}" ;;
                                *) echo "ERROR: could not resolve an OCI digest for ${env.PUBLISHED_IMAGE}" >&2; exit 1 ;;
                            esac
                        """,
                        returnStdout: true
                    ).trim()

                    echo "Resolved published image ${env.PUBLISHED_IMAGE}@${env.DEPLOY_IMAGE_DIGEST}"
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
                expression {
                    params.DEPLOY_ENV == 'prod' &&
                        !params.SKIP_DEPLOY &&
                        env.IS_PR != 'true'
                }
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
                expression { !params.SKIP_DEPLOY && env.IS_PR != 'true' }
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
                        set +x
                        set -eu

                        echo "════════════════════════════════════════════════════"
                        echo "  Deploying StarExec"
                        echo "  Namespace   : ${K8S_NAMESPACE}"
                        echo "  Release     : ${HELM_RELEASE}"
                        echo "  Image       : ${PUBLISHED_IMAGE}@${DEPLOY_IMAGE_DIGEST}"
                        echo "  Values      : ${HELM_VALUES}"
                        echo "════════════════════════════════════════════════════"

                        SECRET_ENV_FILE="${WORKSPACE}/.starexec-db-${BUILD_NUMBER}.env"
                        umask 077
                        printf 'user=%s\npassword=%s\ndatabase=starexec\nrootPassword=%s\n' \\
                            "\${DB_USER}" "\${DB_PASSWORD}" "\${DB_PASSWORD}" > "\${SECRET_ENV_FILE}"
                        trap 'rm -f "\${SECRET_ENV_FILE}"' EXIT

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
                            --from-env-file="\${SECRET_ENV_FILE}" \\
                            --dry-run=client -o yaml | microk8s kubectl apply -f -

                        # -----------------------------------------------------------------
                        # 3. Pre-deploy backup (production only, best-effort)
                        # -----------------------------------------------------------------
                        if [ "${DEPLOY_ENV}" = "prod" ]; then
                            BACKUP_DIR="${env.JENKINS_HOME ?: env.WORKSPACE}/backups/starexec"
                            if mkdir -p "\${BACKUP_DIR}"; then
                                BACKUP_FILE="\${BACKUP_DIR}/pre-deploy-\$(date +%Y%m%d-%H%M%S).sql"
                                if microk8s kubectl get deploy/${HELM_RELEASE} -n ${K8S_NAMESPACE} >/dev/null 2>&1; then
                                    microk8s kubectl exec -n ${K8S_NAMESPACE} deploy/${HELM_RELEASE} -c postgres \\
                                        -- pg_dump -U starexec starexec > "\${BACKUP_FILE}" 2>&1 && \\
                                        echo "✓ Backup: \${BACKUP_FILE} (\$(wc -c < "\${BACKUP_FILE}") bytes)" || \\
                                        echo "⚠ Backup skipped (postgres not reachable)"
                                else
                                    echo "ℹ No existing deployment — backup skipped"
                                fi
                            else
                                echo "⚠ Backup skipped (backup directory not writable: \${BACKUP_DIR})"
                            fi
                        fi

                        # -----------------------------------------------------------------
                        # 4. Helm deploy (atomic — rolls back on failure). Kubernetes
                        #    resolves the public GitHub-published image digest directly.
                        # -----------------------------------------------------------------
                        microk8s helm3 upgrade --install ${HELM_RELEASE} \\
                            charts/starexec \\
                            --namespace ${K8S_NAMESPACE} \\
                            --create-namespace \\
                            --values ${HELM_VALUES} \\
                            --set-string image.tag=${PUBLISHED_IMAGE_TAG} \\
                            --set-string image.digest=${DEPLOY_IMAGE_DIGEST} \\
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
                            echo "ERROR: Database migrations failed; deployment requires intervention."
                            exit 1
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
                    ║  Image:       ${PUBLISHED_IMAGE}@${DEPLOY_IMAGE_DIGEST}  ║
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
                expression { !params.SKIP_DEPLOY && env.IS_PR != 'true' }
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
                expression { !params.SKIP_DEPLOY && env.IS_PR != 'true' }
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
  Published Image: ${env.PUBLISHED_IMAGE}
  Deploy Digest : ${env.DEPLOY_IMAGE_DIGEST}
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
