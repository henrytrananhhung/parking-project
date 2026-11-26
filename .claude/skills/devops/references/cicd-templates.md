# CI/CD Pipeline Templates

## Table of Contents
1. [GitHub Actions — Java + Docker + EKS](#github-actions--java--docker--eks)
2. [GitHub Actions — OIDC (no stored AWS keys)](#github-actions--oidc-no-stored-aws-keys)
3. [GitLab CI — Java + Docker + EKS](#gitlab-ci--java--docker--eks)
4. [Helm deploy stage](#helm-deploy-stage)

---

## GitHub Actions — Java + Docker + EKS

```yaml
# .github/workflows/deploy.yml
name: Build and Deploy

on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

env:
  AWS_REGION: ap-southeast-1
  ECR_REGISTRY: 123456789.dkr.ecr.ap-southeast-1.amazonaws.com
  ECR_REPOSITORY: my-service
  EKS_CLUSTER: my-cluster

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
          cache: maven

      - name: Run tests (Testcontainers works — Docker pre-installed)
        run: ./mvnw verify -B

      - name: Upload test results
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: test-results
          path: target/surefire-reports/

  build-and-push:
    needs: test
    runs-on: ubuntu-latest
    if: github.ref == 'refs/heads/main'
    permissions:
      id-token: write   # required for OIDC
      contents: read

    outputs:
      image-tag: ${{ steps.meta.outputs.version }}

    steps:
      - uses: actions/checkout@v4

      - name: Configure AWS credentials via OIDC
        uses: aws-actions/configure-aws-credentials@v4
        with:
          role-to-assume: arn:aws:iam::123456789:role/github-actions-deploy
          aws-region: ${{ env.AWS_REGION }}

      - name: Login to ECR
        uses: aws-actions/amazon-ecr-login@v2

      - name: Set up Docker Buildx (for layer caching)
        uses: docker/setup-buildx-action@v3

      - name: Extract image metadata
        id: meta
        uses: docker/metadata-action@v5
        with:
          images: ${{ env.ECR_REGISTRY }}/${{ env.ECR_REPOSITORY }}
          tags: |
            type=sha,prefix=,format=short
            type=semver,pattern={{version}}

      - name: Build and push image
        uses: docker/build-push-action@v5
        with:
          context: .
          push: true
          tags: ${{ steps.meta.outputs.tags }}
          labels: ${{ steps.meta.outputs.labels }}
          cache-from: type=gha
          cache-to: type=gha,mode=max
          build-args: |
            BUILD_VERSION=${{ steps.meta.outputs.version }}

      - name: Scan image with Trivy
        uses: aquasecurity/trivy-action@master
        with:
          image-ref: ${{ env.ECR_REGISTRY }}/${{ env.ECR_REPOSITORY }}:${{ steps.meta.outputs.version }}
          format: sarif
          output: trivy-results.sarif
          severity: CRITICAL,HIGH
          exit-code: 1   # fail build on CRITICAL

  deploy-staging:
    needs: build-and-push
    runs-on: ubuntu-latest
    environment: staging
    permissions:
      id-token: write
      contents: read

    steps:
      - uses: actions/checkout@v4

      - name: Configure AWS credentials
        uses: aws-actions/configure-aws-credentials@v4
        with:
          role-to-assume: arn:aws:iam::123456789:role/github-actions-deploy
          aws-region: ${{ env.AWS_REGION }}

      - name: Update kubeconfig
        run: aws eks update-kubeconfig --name ${{ env.EKS_CLUSTER }} --region ${{ env.AWS_REGION }}

      - name: Deploy to staging via Helm
        run: |
          helm upgrade --install my-service ./helm/my-service \
            --namespace staging \
            --set image.tag=${{ needs.build-and-push.outputs.image-tag }} \
            --set replicaCount=2 \
            --values helm/my-service/values-staging.yaml \
            --wait --timeout 5m

      - name: Run smoke tests
        run: |
          kubectl wait --for=condition=ready pod \
            -l app=my-service -n staging --timeout=120s
          # add your smoke test curl / k6 here

  deploy-prod:
    needs: deploy-staging
    runs-on: ubuntu-latest
    environment: production   # requires manual approval in GitHub Environments
    permissions:
      id-token: write
      contents: read

    steps:
      - uses: actions/checkout@v4

      - name: Configure AWS credentials
        uses: aws-actions/configure-aws-credentials@v4
        with:
          role-to-assume: arn:aws:iam::123456789:role/github-actions-deploy-prod
          aws-region: ${{ env.AWS_REGION }}

      - name: Update kubeconfig
        run: aws eks update-kubeconfig --name ${{ env.EKS_CLUSTER }}-prod --region ${{ env.AWS_REGION }}

      - name: Deploy to production via Helm
        run: |
          helm upgrade --install my-service ./helm/my-service \
            --namespace production \
            --set image.tag=${{ needs.build-and-push.outputs.image-tag }} \
            --values helm/my-service/values-prod.yaml \
            --wait --timeout 10m \
            --atomic   # auto-rollback on failure
```

---

## GitHub Actions — OIDC Trust Policy (AWS side)

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Federated": "arn:aws:iam::123456789:oidc-provider/token.actions.githubusercontent.com"
      },
      "Action": "sts:AssumeRoleWithWebIdentity",
      "Condition": {
        "StringEquals": {
          "token.actions.githubusercontent.com:aud": "sts.amazonaws.com"
        },
        "StringLike": {
          "token.actions.githubusercontent.com:sub": "repo:your-org/your-repo:*"
        }
      }
    }
  ]
}
```

No access keys in GitHub Secrets. Zero credential rotation. Auditable per-repo.

---

## GitLab CI — Java + Docker + EKS

```yaml
# .gitlab-ci.yml
stages:
  - test
  - build
  - scan
  - deploy-staging
  - deploy-prod

variables:
  MAVEN_OPTS: "-Dmaven.repo.local=.m2/repository"
  DOCKER_HOST: tcp://docker:2375
  DOCKER_TLS_CERTDIR: ""
  ECR_REGISTRY: "123456789.dkr.ecr.ap-southeast-1.amazonaws.com"
  IMAGE_NAME: "$ECR_REGISTRY/my-service"

cache:
  paths:
    - .m2/repository/

test:
  stage: test
  image: maven:3.9-eclipse-temurin-21
  services:
    - name: docker:26-dind
      alias: docker
  variables:
    TESTCONTAINERS_HOST_OVERRIDE: docker
  script:
    - mvn verify -B
  artifacts:
    reports:
      junit: target/surefire-reports/TEST-*.xml
    when: always

build:
  stage: build
  image: docker:26
  services:
    - docker:26-dind
  only:
    - main
  before_script:
    - apk add --no-cache python3 py3-pip
    - pip3 install awscli --break-system-packages
    - aws ecr get-login-password --region ap-southeast-1 |
        docker login --username AWS --password-stdin $ECR_REGISTRY
  script:
    - export IMAGE_TAG=$CI_COMMIT_SHORT_SHA
    - docker build
        --cache-from $IMAGE_NAME:latest
        --build-arg BUILDKIT_INLINE_CACHE=1
        -t $IMAGE_NAME:$IMAGE_TAG
        -t $IMAGE_NAME:latest .
    - docker push $IMAGE_NAME:$IMAGE_TAG
    - docker push $IMAGE_NAME:latest
    - echo "IMAGE_TAG=$IMAGE_TAG" >> build.env
  artifacts:
    reports:
      dotenv: build.env

scan:
  stage: scan
  image:
    name: aquasec/trivy:latest
    entrypoint: [""]
  only:
    - main
  needs: [build]
  script:
    - trivy image
        --exit-code 1
        --severity CRITICAL
        --no-progress
        $IMAGE_NAME:$IMAGE_TAG

deploy-staging:
  stage: deploy-staging
  image: bitnami/kubectl:latest
  only:
    - main
  needs: [scan]
  environment:
    name: staging
  before_script:
    - aws eks update-kubeconfig --name my-cluster --region ap-southeast-1
  script:
    - helm upgrade --install my-service ./helm/my-service
        --namespace staging
        --set image.tag=$IMAGE_TAG
        --values helm/my-service/values-staging.yaml
        --wait --timeout 5m

deploy-prod:
  stage: deploy-prod
  image: bitnami/kubectl:latest
  only:
    - main
  needs: [deploy-staging]
  when: manual   # requires human approval
  environment:
    name: production
  script:
    - aws eks update-kubeconfig --name my-cluster-prod --region ap-southeast-1
    - helm upgrade --install my-service ./helm/my-service
        --namespace production
        --set image.tag=$IMAGE_TAG
        --values helm/my-service/values-prod.yaml
        --wait --timeout 10m
        --atomic
```

---

## Helm Deploy Stage (reusable)

```bash
#!/bin/bash
# scripts/helm-deploy.sh — called from any CI system

set -euo pipefail

NAMESPACE=${1:?namespace required}
IMAGE_TAG=${2:?image tag required}
VALUES_FILE=${3:?values file required}

helm upgrade --install my-service ./helm/my-service \
  --namespace "$NAMESPACE" \
  --create-namespace \
  --set image.repository="$ECR_REGISTRY/my-service" \
  --set image.tag="$IMAGE_TAG" \
  --values "$VALUES_FILE" \
  --wait \
  --timeout 10m \
  --atomic \
  --history-max 5
```
