{{- define "pgbouncer-pooling-demo.name" -}}
{{- default .Chart.Name .Values.nameOverride -}}
{{- end -}}

{{- define "pgbouncer-pooling-demo.fullname" -}}
{{- default .Release.Name .Values.fullnameOverride -}}
{{- end -}}

{{- define "pgbouncer-pooling-demo.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "pgbouncer-pooling-demo.labels" -}}
helm.sh/chart: {{ include "pgbouncer-pooling-demo.chart" . }}
{{ include "pgbouncer-pooling-demo.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

{{- define "pgbouncer-pooling-demo.selectorLabels" -}}
app.kubernetes.io/name: {{ include "pgbouncer-pooling-demo.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "pgbouncer-pooling-demo.imagePullPolicy" -}}
{{- default .Values.global.imagePullPolicy .Values.imagePullPolicy -}}
{{- end -}}

{{- define "pgbouncer-pooling-demo.image" -}}
{{- printf "%s:%s" .repository .tag -}}
{{- end -}}