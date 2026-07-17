{{- /*
Common helpers used by templates.
*/ -}}

{{- define "chart.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "chart.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- define "chart.labels" -}}
helm.sh/chart: {{ include "chart.name" . }}-{{ .Chart.Version | replace "+" "_" }}
{{ include "chart.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

{{- define "chart.selectorLabels" -}}
app.kubernetes.io/name: {{ include "chart.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- /*
Generate a volume name based on the volumePrefix and volume type.
Usage: {{ include "starexec.volumeName" (list . "data") }}
*/ -}}
{{- define "starexec.volumeName" -}}
{{- $root := index . 0 -}}
{{- $volumeType := index . 1 -}}
{{- $env := default "dev" $root.Values.environment -}}
{{- printf "%s-%s-%s" (default "starexec" $root.Values.volumePrefix) $env $volumeType -}}
{{- end -}}

{{- define "starexec.isKubernetesBackend" -}}
{{- if or (eq .Values.backend.type "kubernetes") (eq .Values.backend.type "k8s") (eq .Values.backend.type "kubernetes-native") -}}
true
{{- else -}}
false
{{- end -}}
{{- end -}}

{{- define "starexec.usesEmbeddedPostgres" -}}
{{- if eq .Values.postgres.host "localhost" -}}
true
{{- else -}}
false
{{- end -}}
{{- end -}}

{{- define "starexec.appImage" -}}
{{- if .Values.image.digest -}}
{{- printf "%s@%s" .Values.image.repository .Values.image.digest -}}
{{- else -}}
{{- printf "%s:%s" .Values.image.repository .Values.image.tag -}}
{{- end -}}
{{- end -}}

{{- define "starexec.k8sJobServiceAccount" -}}
{{- default (printf "%s-job" (include "chart.fullname" .)) .Values.kubernetes.jobServiceAccount -}}
{{- end -}}

{{- define "starexec.k8sDataPvcName" -}}
{{- default (include "starexec.volumeName" (list . "data")) .Values.kubernetes.dataPvc.name -}}
{{- end -}}
