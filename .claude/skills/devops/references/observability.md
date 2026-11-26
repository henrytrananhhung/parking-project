# Observability — Spring Boot on Kubernetes

## Table of Contents
1. [Spring Boot config](#spring-boot-config)
2. [Prometheus scrape config](#prometheus-scrape-config)
3. [Key alert rules](#key-alert-rules)
4. [OpenTelemetry tracing](#opentelemetry-tracing)
5. [Grafana dashboard queries](#grafana-dashboard-queries)

---

## Spring Boot Config

```yaml
# application-prod.yml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics
      base-path: /actuator
  endpoint:
    health:
      show-details: always
      probes:
        enabled: true         # enables /actuator/health/liveness and /readiness
  metrics:
    tags:
      application: ${spring.application.name}
      environment: ${spring.profiles.active}
    distribution:
      percentiles-histogram:
        http.server.requests: true    # enables p50/p95/p99 histograms
      slo:
        http.server.requests: 50ms,100ms,200ms,500ms,1s

# OpenTelemetry exporter
otel:
  exporter:
    otlp:
      endpoint: http://otel-collector:4317
  resource:
    attributes:
      service.name: ${spring.application.name}
      service.version: ${APP_VERSION:unknown}
      deployment.environment: ${spring.profiles.active}
```

---

## Prometheus Scrape Config

```yaml
# prometheus.yml (or ServiceMonitor if using Prometheus Operator)
scrape_configs:
  - job_name: 'spring-boot-services'
    kubernetes_sd_configs:
      - role: pod
    relabel_configs:
      - source_labels: [__meta_kubernetes_pod_annotation_prometheus_io_scrape]
        action: keep
        regex: true
      - source_labels: [__meta_kubernetes_pod_annotation_prometheus_io_path]
        action: replace
        target_label: __metrics_path__
        regex: (.+)
      - source_labels: [__address__, __meta_kubernetes_pod_annotation_prometheus_io_port]
        action: replace
        regex: ([^:]+)(?::\d+)?;(\d+)
        replacement: $1:$2
        target_label: __address__
      - source_labels: [__meta_kubernetes_namespace]
        target_label: namespace
      - source_labels: [__meta_kubernetes_pod_name]
        target_label: pod
      - source_labels: [__meta_kubernetes_pod_label_app]
        target_label: app
```

For Prometheus Operator (recommended):
```yaml
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: my-service
  namespace: production
spec:
  selector:
    matchLabels:
      app: my-service
  endpoints:
    - port: http
      path: /actuator/prometheus
      interval: 15s
```

---

## Key Alert Rules

```yaml
# alerts/spring-boot.yaml
groups:
  - name: spring-boot-slo
    rules:

      # Error rate > 1% for 5 minutes
      - alert: HighErrorRate
        expr: |
          sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m])) by (app, namespace)
          /
          sum(rate(http_server_requests_seconds_count[5m])) by (app, namespace)
          > 0.01
        for: 5m
        labels:
          severity: critical
        annotations:
          summary: "High error rate on {{ $labels.app }}"
          description: "Error rate is {{ $value | humanizePercentage }}"

      # p99 latency > 2s
      - alert: HighLatency
        expr: |
          histogram_quantile(0.99,
            sum(rate(http_server_requests_seconds_bucket[5m])) by (le, app, namespace)
          ) > 2
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "High p99 latency on {{ $labels.app }}"

      # Pod restarts — CrashLoopBackOff indicator
      - alert: PodCrashLooping
        expr: |
          increase(kube_pod_container_status_restarts_total[15m]) > 3
        for: 5m
        labels:
          severity: critical
        annotations:
          summary: "Pod crash looping: {{ $labels.pod }}"

      # HPA at max replicas — need to scale the max
      - alert: HPAAtMaxReplicas
        expr: |
          kube_horizontalpodautoscaler_status_current_replicas
          ==
          kube_horizontalpodautoscaler_spec_max_replicas
        for: 15m
        labels:
          severity: warning
        annotations:
          summary: "HPA maxed out for {{ $labels.horizontalpodautoscaler }}"

      # JVM heap usage > 85%
      - alert: JvmHeapPressure
        expr: |
          jvm_memory_used_bytes{area="heap"}
          /
          jvm_memory_max_bytes{area="heap"}
          > 0.85
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "JVM heap pressure on {{ $labels.app }}: {{ $value | humanizePercentage }}"
```

---

## OpenTelemetry Tracing

```xml
<!-- pom.xml — zero-code instrumentation via Java agent -->
<dependency>
    <groupId>io.opentelemetry.instrumentation</groupId>
    <artifactId>opentelemetry-spring-boot-starter</artifactId>
    <version>2.3.0</version>
</dependency>
```

```yaml
# Kubernetes: inject OTEL config via env vars on the pod
env:
  - name: OTEL_SERVICE_NAME
    value: my-service
  - name: OTEL_EXPORTER_OTLP_ENDPOINT
    value: http://opentelemetry-collector.monitoring:4317
  - name: OTEL_TRACES_SAMPLER
    value: parentbased_traceidratio
  - name: OTEL_TRACES_SAMPLER_ARG
    value: "0.1"    # sample 10% in prod — adjust to your volume
  - name: OTEL_PROPAGATORS
    value: tracecontext,baggage,b3
```

---

## Grafana Dashboard Queries

```promql
# Request rate (RPS)
sum(rate(http_server_requests_seconds_count{app="my-service"}[1m])) by (uri, method)

# Error rate %
100 * sum(rate(http_server_requests_seconds_count{app="my-service", status=~"5.."}[5m]))
/ sum(rate(http_server_requests_seconds_count{app="my-service"}[5m]))

# p50 / p95 / p99 latency
histogram_quantile(0.99,
  sum(rate(http_server_requests_seconds_bucket{app="my-service"}[5m])) by (le)
)

# JVM heap usage
jvm_memory_used_bytes{app="my-service", area="heap"}
/ jvm_memory_max_bytes{app="my-service", area="heap"} * 100

# Active Kafka consumer lag
kafka_consumer_fetch_manager_records_lag_max{app="my-service"}

# HikariCP connection pool utilization
hikaricp_connections_active{app="my-service"}
/ hikaricp_connections_max{app="my-service"} * 100
```
