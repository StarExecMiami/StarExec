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
                        # 2b. Pre-flight: refuse to deploy over a wedged release.
                        #     A failed nfs-server export makes every NFS-backed PVC
                        #     unserviceable and blocks postgres I/O in D state, which no
                        #     signal can kill (prod outage, 2026-09-17). A pod stuck
                        #     Terminating (kubelet FailedKillPod) holds its RWO volumes,
                        #     so the rollout and its --atomic rollback both hang until
                        #     the Helm timeout (build #137). A release left in 'failed'
                        #     state must be rolled back before the next attempt.
                        # -----------------------------------------------------------------
                        if ! systemctl is-active --quiet nfs-server; then
                            echo "ERROR: nfs-server.service is not active; NFS-backed PVCs cannot mount."
                            echo "Recover first:  sudo systemctl restart nfs-server"
                            echo "Then verify:    sudo exportfs -v"
                            echo "See docs/TROUBLESHOOTING.md, section 'NFS server outage'."
                            exit 1
                        fi
                        STUCK_PODS=\$(timeout 60 microk8s kubectl -n ${K8S_NAMESPACE} get pods -o json 2>/dev/null \\
                            | jq -r '.items[] | select(.metadata.deletionTimestamp != null) | .metadata.name' || true)
                        if [ -n "\${STUCK_PODS}" ]; then
                            echo "ERROR: pod(s) stuck Terminating; a rollout cannot release their volumes:"
                            echo "\${STUCK_PODS}"
                            microk8s kubectl -n ${K8S_NAMESPACE} get pods -o wide || true
                            exit 1
                        fi
                        NOT_READY_PODS=\$(timeout 60 microk8s kubectl -n ${K8S_NAMESPACE} get pods -o json 2>/dev/null \\
                            | jq -r '.items[] | select(.status.phase == "Running") | select(any(.status.containerStatuses[]?; .ready == false)) | .metadata.name' || true)
                        if [ -n "\${NOT_READY_PODS}" ]; then
                            echo "ERROR: pod(s) running but not ready; refusing to deploy over an unhealthy release:"
                            echo "\${NOT_READY_PODS}"
                            exit 1
                        fi
                        RELEASE_STATUS=\$(timeout 60 microk8s helm3 status ${HELM_RELEASE} -n ${K8S_NAMESPACE} -o json 2>/dev/null \\
                            | jq -r '.info.status // empty' || true)
                        if [ "\${RELEASE_STATUS}" = "failed" ]; then
                            echo "ERROR: Helm release ${HELM_RELEASE} is in 'failed' state."
                            echo "Roll it back to the last deployed revision before retrying:"
                            echo "  microk8s helm3 history ${HELM_RELEASE} -n ${K8S_NAMESPACE}"
                            echo "  microk8s helm3 rollback ${HELM_RELEASE} <last-deployed-revision> -n ${K8S_NAMESPACE}"
                            exit 1
                        fi

                        # -----------------------------------------------------------------
                        # 3. Pre-deploy backup (production only, required)
                        # -----------------------------------------------------------------
                        if [ "${DEPLOY_ENV}" = "prod" ]; then
                            BACKUP_DIR="${env.JENKINS_HOME ?: env.WORKSPACE}/backups/starexec"
                            if mkdir -p "\${BACKUP_DIR}"; then
                                BACKUP_FILE="\${BACKUP_DIR}/pre-deploy-\$(date +%Y%m%d-%H%M%S).sql.gz"
                                TMP_BACKUP_FILE="\${BACKUP_FILE}.tmp"
                                DUMP_STATUS_FILE="\${TMP_BACKUP_FILE}.status"
                                if microk8s kubectl get deploy/${HELM_RELEASE} -n ${K8S_NAMESPACE} >/dev/null 2>&1; then
                                    COMPRESS_CMD="gzip -1"
                                    if command -v ionice >/dev/null 2>&1; then
                                        COMPRESS_CMD="ionice -c 3 gzip -1"
                                    fi
                                    rm -f "\${TMP_BACKUP_FILE}" "\${DUMP_STATUS_FILE}"
                                    (
                                        if timeout 300 microk8s kubectl exec -n ${K8S_NAMESPACE} deploy/${HELM_RELEASE} -c postgres \\
                                            -- pg_dump -U starexec starexec; then
                                            echo "0" > "\${DUMP_STATUS_FILE}"
                                        else
                                            echo "\$?" > "\${DUMP_STATUS_FILE}"
                                        fi
                                    ) | \${COMPRESS_CMD} > "\${TMP_BACKUP_FILE}" || true
                                    DUMP_RC=\$(cat "\${DUMP_STATUS_FILE}" 2>/dev/null || echo "1")
                                    rm -f "\${DUMP_STATUS_FILE}"
                                    if [ "\${DUMP_RC}" -eq 0 ] && [ -s "\${TMP_BACKUP_FILE}" ] && gzip -t "\${TMP_BACKUP_FILE}"; then
                                        mv -f "\${TMP_BACKUP_FILE}" "\${BACKUP_FILE}"
                                        echo "✓ Backup: \${BACKUP_FILE} (\$(wc -c < "\${BACKUP_FILE}") bytes)"
                                        ls -1t "\${BACKUP_DIR}"/pre-deploy-*.sql* 2>/dev/null | sed -n '6,\$p' | xargs -r rm -f || true
                                    else
                                        rm -f "\${TMP_BACKUP_FILE}"
                                        echo "ERROR: pre-deploy backup failed (postgres not reachable or archive corrupt)."
                                        echo "Refusing to deploy a schema-affecting revision without a backup."
                                        exit 1
                                    fi
                                else
                                    echo "ℹ No existing deployment — backup skipped"
                                fi
                            else
                                echo "ERROR: backup directory not writable: \${BACKUP_DIR}"
                                exit 1
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
                    ║  Deploy aborted or rolled back; see the log above.      ║
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

        // =======================================================================
        stage('Deployed E2E Matrix (Kubernetes)') {
        // =======================================================================
            when {
                expression { !params.SKIP_DEPLOY && env.IS_PR != 'true' }
            }
            steps {
                script {
                    def matrixMode = (params.DEPLOY_ENV == 'prod') ? 'upgrade' : 'fresh'
                    sh """
                        set -eu
                        TMP_E2E=\$(mktemp -d /tmp/starexec-k8s-e2e-XXXXXX)
                        trap 'rm -rf "\${TMP_E2E}"' EXIT

                        mkdir -p target/e2e-reports/kubernetes
                        python3 -m venv "\${TMP_E2E}/venv"
                        "\${TMP_E2E}/venv/bin/pip" install --quiet requests bcrypt

                        "\${TMP_E2E}/venv/bin/python" scripts/e2e/run.py --verify-endpoints --commit "${env.GIT_SHA}"
                        "\${TMP_E2E}/venv/bin/python" scripts/e2e/run.py --fixtures-only --fixture-mode reproducible-offline

                        if [ "${params.DEPLOY_ENV}" = "prod" ] || [ "${env.K8S_NAMESPACE}" = "starexec" ]; then
                            echo "Refusing mutating E2E password-reset checks against production namespace ${env.K8S_NAMESPACE}; recording BLOCKED matrix report."
                            set +e
                            "\${TMP_E2E}/venv/bin/python" scripts/e2e/run.py \\
                                --record-blocked \\
                                --backend kubernetes \\
                                --mode "${matrixMode}" \\
                                --commit "${env.GIT_SHA}" \\
                                --reason "disposable sq-* guard forbids E2E account mutation on production namespace" \\
                                --matrix-out target/e2e-reports/kubernetes
                            RC=\$?
                            set -e
                            test "\${RC}" -eq 2
                            exit 0
                        fi

                        SHORT_SHA=\$(printf '%s' "${env.GIT_SHA}" | cut -c1-8)
                        cat > "\${TMP_E2E}/k8s-e2e-config.json" <<EOF
                        {
                          "backend": "kubernetes",
                          "mode": "${matrixMode}",
                          "fixture_mode": "reproducible-offline",
                          "base_url": "${env.APP_URL}",
                          "deployment_name": "sq-\${SHORT_SHA}-k8s",
                          "db_exec": ["microk8s", "kubectl", "exec", "-i", "-n", "${env.K8S_NAMESPACE}", "deploy/${env.HELM_RELEASE}", "-c", "postgres", "--", "psql", "-U", "starexec", "-d", "starexec", "-X", "-qAt", "-v", "ON_ERROR_STOP=1"],
                          "app_exec": ["microk8s", "kubectl", "exec", "-i", "-n", "${env.K8S_NAMESPACE}", "deploy/${env.HELM_RELEASE}", "-c", "app", "--"],
                          "app_data_root": "/app/data",
                          "queue_id": "auto",
                          "commit": "${env.GIT_SHA}",
                          "image": "${env.PUBLISHED_IMAGE}",
                          "image_digest": "${env.DEPLOY_IMAGE_DIGEST}",
                          "report_dir": "\${PWD}/target/e2e-reports/kubernetes",
                          "allowed_hosts": ["quokka.acorn.miami.edu"],
                          "admin_user_id": 1,
                          "enable_browser": false,
                          "timeouts": {"upload": 300, "job": 1200, "pause": 120, "kill": 120, "dispatch": 180, "smtp": 60}
                        }
                        EOF

                        "\${TMP_E2E}/venv/bin/python" scripts/e2e/run.py \\
                            --config "\${TMP_E2E}/k8s-e2e-config.json" \\
                            --backend kubernetes \\
                            --mode "${matrixMode}" \\
                            --image-digest "${env.DEPLOY_IMAGE_DIGEST}" \\
                            --matrix-out target/e2e-reports/kubernetes
                    """
                }
            }
            post {
                always {
                    archiveArtifacts artifacts: 'target/e2e-reports/kubernetes/**', allowEmptyArchive: true
                }
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
