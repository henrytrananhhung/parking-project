# Kubernetes Production Manifests

## Table of Contents
1. [Deployment](#deployment)
2. [HorizontalPodAutoscaler](#horizontalpodautoscaler)
3. [PodDisruptionBudget](#poddisruptionbudget)
4. [NetworkPolicy](#networkpolicy)
5. [ServiceAccount with IRSA](#serviceaccount-with-irsa)
6. [Helm values structure](#helm-values-structure)

---

## Deployment

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: my-service
  namespace: production
  labels:
    app: my-service
    version: "1.0.0"
spec:
  replicas: 3
  selector:
    matchLabels:
      app: my-service
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 1
      maxUnavailable: 0      # never reduce capacity during rollout
  template:
    metadata:
      labels:
        app: my-service
      annotations:
        prometheus.io/scrape: "true"
        prometheus.io/port: "8080"
        prometheus.io/path: "/actuator/prometheus"
    spec:
      serviceAccountName: my-service   # IRSA-bound SA
      securityContext:
        runAsNonRoot: true
        runAsUser: 1000
        fsGroup: 1000
      topologySpreadConstraints:
        - maxSkew: 1
          topologyKey: topology.kubernetes.io/zone
          whenUnsatisfiable: DoNotSchedule
          labelSelector:
            matchLabels:
              app: my-service
      containers:
        - name: my-service
          image: 123456789.dkr.ecr.ap-southeast-1.amazonaws.com/my-service:abc1234
          imagePullPolicy: IfNotPresent
          ports:
            - containerPort: 8080
              name: http
          env:
            - name: SPRING_PROFILES_ACTIVE
              value: "prod"
            - name: JAVA_OPTS
              value: >-
                -XX:+UseContainerSupport
                -XX:MaxRAMPercentage=75.0
                -XX:+ExitOnOutOfMemoryError
                -Djava.security.egd=file:/dev/./urandom
          envFrom:
            - secretRef:
                name: my-service-secrets   # from External Secrets Operator
          resources:
            requests:
              memory: "512Mi"
              cpu: "250m"
            limits:
              memory: "1Gi"
              cpu: "1000m"
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: 8080
            initialDelaySeconds: 20
            periodSeconds: 10
            failureThreshold: 3
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: 8080
            initialDelaySeconds: 30
            periodSeconds: 15
            failureThreshold: 3
          securityContext:
            allowPrivilegeEscalation: false
            readOnlyRootFilesystem: true
            capabilities:
              drop: [ALL]
          volumeMounts:
            - name: tmp
              mountPath: /tmp       # needed if readOnlyRootFilesystem: true
      volumes:
        - name: tmp
          emptyDir: {}
      terminationGracePeriodSeconds: 60   # give Spring Boot time for graceful shutdown
```

---

## HorizontalPodAutoscaler

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: my-service
  namespace: production
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: my-service
  minReplicas: 3
  maxReplicas: 20
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 60    # scale before saturation
    - type: Resource
      resource:
        name: memory
        target:
          type: Utilization
          averageUtilization: 75
  behavior:
    scaleDown:
      stabilizationWindowSeconds: 300   # 5 min cool-down before scaling down
      policies:
        - type: Pods
          value: 1
          periodSeconds: 60             # remove 1 pod per minute max
    scaleUp:
      stabilizationWindowSeconds: 0    # scale up immediately
      policies:
        - type: Pods
          value: 4
          periodSeconds: 60            # add up to 4 pods per minute
```

---

## PodDisruptionBudget

```yaml
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: my-service
  namespace: production
spec:
  minAvailable: 2          # always keep at least 2 pods during node drain / upgrades
  selector:
    matchLabels:
      app: my-service
```

Without this, `kubectl drain` during a node upgrade can evict all pods simultaneously.

---

## NetworkPolicy

```yaml
# Default deny all — add explicit allows below
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: default-deny-all
  namespace: production
spec:
  podSelector: {}
  policyTypes:
    - Ingress
    - Egress

---
# Allow my-service to receive traffic from ingress controller only
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: my-service-ingress
  namespace: production
spec:
  podSelector:
    matchLabels:
      app: my-service
  policyTypes:
    - Ingress
  ingress:
    - from:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: ingress-nginx
      ports:
        - protocol: TCP
          port: 8080

---
# Allow my-service to reach RDS and Redis (by CIDR or namespace)
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: my-service-egress
  namespace: production
spec:
  podSelector:
    matchLabels:
      app: my-service
  policyTypes:
    - Egress
  egress:
    - ports:
        - port: 5432    # PostgreSQL RDS
        - port: 6379    # Redis
        - port: 9092    # Kafka MSK
        - port: 443     # AWS API calls (Secrets Manager, S3)
        - port: 53      # DNS
      protocol: TCP
    - ports:
        - port: 53
      protocol: UDP
```

---

## ServiceAccount with IRSA

```yaml
# Kubernetes ServiceAccount — annotated with IAM role ARN
apiVersion: v1
kind: ServiceAccount
metadata:
  name: my-service
  namespace: production
  annotations:
    eks.amazonaws.com/role-arn: arn:aws:iam::123456789:role/my-service-prod
```

```hcl
# Terraform — IAM role trusted by the EKS OIDC provider
module "my_service_irsa" {
  source  = "terraform-aws-modules/iam/aws//modules/iam-role-for-service-accounts-eks"
  version = "~> 5.0"

  role_name = "my-service-prod"

  oidc_providers = {
    main = {
      provider_arn               = module.eks.oidc_provider_arn
      namespace_service_accounts = ["production:my-service"]
    }
  }
}

# Attach only what the service actually needs
resource "aws_iam_role_policy" "my_service" {
  name = "my-service-policy"
  role = module.my_service_irsa.iam_role_name

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["secretsmanager:GetSecretValue"]
        Resource = "arn:aws:secretsmanager:ap-southeast-1:123456789:secret:prod/my-service/*"
      },
      {
        Effect   = "Allow"
        Action   = ["s3:GetObject", "s3:PutObject"]
        Resource = "arn:aws:s3:::my-service-uploads-prod/*"
      }
    ]
  })
}
```

---

## Helm Values Structure

```yaml
# helm/my-service/values-prod.yaml
replicaCount: 3

image:
  repository: 123456789.dkr.ecr.ap-southeast-1.amazonaws.com/my-service
  tag: ""          # always overridden by CI via --set image.tag=
  pullPolicy: IfNotPresent

service:
  type: ClusterIP
  port: 8080

ingress:
  enabled: true
  className: alb
  annotations:
    kubernetes.io/ingress.class: alb
    alb.ingress.kubernetes.io/scheme: internet-facing
    alb.ingress.kubernetes.io/target-type: ip
    alb.ingress.kubernetes.io/certificate-arn: arn:aws:acm:...
    alb.ingress.kubernetes.io/ssl-redirect: "443"
  hosts:
    - host: api.myapp.com
      paths:
        - path: /
          pathType: Prefix

resources:
  requests:
    memory: "512Mi"
    cpu: "250m"
  limits:
    memory: "1Gi"
    cpu: "1000m"

autoscaling:
  enabled: true
  minReplicas: 3
  maxReplicas: 20
  targetCPUUtilizationPercentage: 60

podDisruptionBudget:
  enabled: true
  minAvailable: 2

externalSecrets:
  enabled: true
  secretStoreName: aws-secrets-manager
  refreshInterval: 1h
  data:
    - secretKey: DB_PASSWORD
      remoteRef:
        key: prod/my-service/db
        property: password
    - secretKey: REDIS_PASSWORD
      remoteRef:
        key: prod/my-service/redis
        property: password
```
