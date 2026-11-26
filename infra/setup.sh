#!/usr/bin/env bash
# One-time GCP foundation for the Parking Reservation demo (Phase 1).
# Run in Google Cloud Shell (gcloud is pre-authenticated there):
#   PROJECT_ID=my-project REGION=asia-southeast1 bash setup.sh
# Re-runnable: each step skips resources that already exist.
#
# Phase 1 foundation only: do NOT re-run it for M3. The M3 changes (Cloud SQL upgrade, Memorystore parking-redis,
# secret redis-password) are in infra/setup-m3.ps1, explained step by step in docs/m3-gcp-setup.md.
# Created by hand, not by this script: VM parking-bastion (e2-micro, parking-run-subnet, OS Login, no external IP)
# and firewall rule parking-allow-iap-ssh (tcp:22 from the IAP range 35.235.240.0/20).
#
# Prerequisites (console): project created, billing account linked.
set -euo pipefail

: "${PROJECT_ID:?Set PROJECT_ID}"
REGION="${REGION:-asia-southeast1}"          # Keep every resource in one region, close to the team.
BILLING_ACCOUNT="${BILLING_ACCOUNT:-}"        # Optional: XXXXXX-XXXXXX-XXXXXX to create the budget alert.

VPC=parking-vpc
SUBNET=parking-run-subnet                     # Direct VPC egress subnet (/26 or larger)
SUBNET_RANGE=10.10.0.0/24
PSA_RANGE=google-managed-services-parking     # Private Services Access range for Cloud SQL
SQL_INSTANCE=parking-db
SQL_TIER=db-g1-small                          # Shared core, 50 max connections. M3: infra/setup-m3.ps1 -Step sql-upgrade.
DB_NAME=parking
DB_USER=parking_app
KEYRING=parking-keyring
KMS_KEY=password-key
RUN_SA=parking-run-sa
AR_REPO=cloud-run-source-deploy               # Default repo used by `gcloud run deploy --source`

gcloud config set project "$PROJECT_ID" >/dev/null
PROJECT_NUMBER="$(gcloud projects describe "$PROJECT_ID" --format='value(projectNumber)')"
RUN_SA_EMAIL="$RUN_SA@$PROJECT_ID.iam.gserviceaccount.com"
exists() { "$@" >/dev/null 2>&1; }
step() { echo; echo "==> $*"; }

step "1. Enable APIs"
gcloud services enable \
  run.googleapis.com sqladmin.googleapis.com compute.googleapis.com servicenetworking.googleapis.com \
  cloudkms.googleapis.com secretmanager.googleapis.com artifactregistry.googleapis.com \
  cloudbuild.googleapis.com logging.googleapis.com monitoring.googleapis.com iam.googleapis.com \
  billingbudgets.googleapis.com

step "2. VPC, Direct VPC egress subnet, Private Services Access"
exists gcloud compute networks describe "$VPC" ||
  gcloud compute networks create "$VPC" --subnet-mode=custom
exists gcloud compute networks subnets describe "$SUBNET" --region="$REGION" ||
  gcloud compute networks subnets create "$SUBNET" --network="$VPC" --region="$REGION" \
    --range="$SUBNET_RANGE" --enable-private-ip-google-access
exists gcloud compute addresses describe "$PSA_RANGE" --global ||
  gcloud compute addresses create "$PSA_RANGE" --global --purpose=VPC_PEERING \
    --prefix-length=20 --network="$VPC"
gcloud services vpc-peerings connect --service=servicenetworking.googleapis.com \
  --ranges="$PSA_RANGE" --network="$VPC" 2>/dev/null ||
  echo "    (peering already exists)"

step "3. Cloud SQL PostgreSQL: private IP only, SSL enforced (started async; takes ~10-15 min)"
if ! exists gcloud sql instances describe "$SQL_INSTANCE"; then
  gcloud sql instances create "$SQL_INSTANCE" \
    --database-version=POSTGRES_17 --edition=ENTERPRISE --tier="$SQL_TIER" \
    --region="$REGION" --availability-type=ZONAL \
    --storage-type=SSD --storage-size=10 \
    --network="projects/$PROJECT_ID/global/networks/$VPC" --no-assign-ip \
    --ssl-mode=ENCRYPTED_ONLY \
    --async
fi

step "4. Secret Manager: db-password, jwt-signing-key (random values, never printed)"
exists gcloud secrets describe db-password ||
  openssl rand -base64 24 | tr -d '\n' | gcloud secrets create db-password --replication-policy=automatic --data-file=-
exists gcloud secrets describe jwt-signing-key ||
  openssl rand -base64 48 | tr -d '\n' | gcloud secrets create jwt-signing-key --replication-policy=automatic --data-file=-

step "5. Cloud KMS: key ring + symmetric key for password protection"
exists gcloud kms keyrings describe "$KEYRING" --location="$REGION" ||
  gcloud kms keyrings create "$KEYRING" --location="$REGION"
exists gcloud kms keys describe "$KMS_KEY" --keyring="$KEYRING" --location="$REGION" ||
  gcloud kms keys create "$KMS_KEY" --keyring="$KEYRING" --location="$REGION" --purpose=encryption

step "6. Runtime service account (least privilege)"
exists gcloud iam service-accounts describe "$RUN_SA_EMAIL" ||
  gcloud iam service-accounts create "$RUN_SA" --display-name="Parking Reservation Cloud Run"
for role in roles/cloudsql.client roles/logging.logWriter roles/monitoring.metricWriter; do
  gcloud projects add-iam-policy-binding "$PROJECT_ID" --member="serviceAccount:$RUN_SA_EMAIL" \
    --role="$role" --condition=None >/dev/null
done
for secret in db-password jwt-signing-key; do
  gcloud secrets add-iam-policy-binding "$secret" --member="serviceAccount:$RUN_SA_EMAIL" \
    --role=roles/secretmanager.secretAccessor >/dev/null
done
gcloud kms keys add-iam-policy-binding "$KMS_KEY" --keyring="$KEYRING" --location="$REGION" \
  --member="serviceAccount:$RUN_SA_EMAIL" --role=roles/cloudkms.cryptoKeyEncrypterDecrypter >/dev/null

step "7. Artifact Registry + build permissions for 'gcloud run deploy --source'"
exists gcloud artifacts repositories describe "$AR_REPO" --location="$REGION" ||
  gcloud artifacts repositories create "$AR_REPO" --repository-format=docker --location="$REGION"
gcloud projects add-iam-policy-binding "$PROJECT_ID" \
  --member="serviceAccount:$PROJECT_NUMBER-compute@developer.gserviceaccount.com" \
  --role=roles/run.builder --condition=None >/dev/null

if [[ -n "$BILLING_ACCOUNT" ]]; then
  step "8. Budget alert (USD 150; 25/50/90/100%)"
  gcloud billing budgets list --billing-account="$BILLING_ACCOUNT" --format='value(displayName)' |
    grep -qx parking-demo-budget ||
    gcloud billing budgets create --billing-account="$BILLING_ACCOUNT" --display-name=parking-demo-budget \
      --budget-amount=150USD --filter-projects="projects/$PROJECT_ID" \
      --threshold-rule=percent=0.25 --threshold-rule=percent=0.5 \
      --threshold-rule=percent=0.9 --threshold-rule=percent=1.0
else
  step "8. Budget alert skipped (set BILLING_ACCOUNT, or create it in Billing > Budgets & alerts)"
fi

step "9. Wait for Cloud SQL, then create database and app user"
until [[ "$(gcloud sql instances describe "$SQL_INSTANCE" --format='value(state)')" == "RUNNABLE" ]]; do
  echo "    waiting for $SQL_INSTANCE ..."; sleep 20
done
exists gcloud sql databases describe "$DB_NAME" --instance="$SQL_INSTANCE" ||
  gcloud sql databases create "$DB_NAME" --instance="$SQL_INSTANCE"
if ! gcloud sql users list --instance="$SQL_INSTANCE" --format='value(name)' | grep -qx "$DB_USER"; then
  gcloud sql users create "$DB_USER" --instance="$SQL_INSTANCE" \
    --password="$(gcloud secrets versions access latest --secret=db-password)"
fi

step "10. Verify"
gcloud sql instances describe "$SQL_INSTANCE" \
  --format='table(name,state,databaseVersion,settings.tier,ipAddresses[].type.list():label=IP_TYPES,settings.ipConfiguration.sslMode:label=SSL_MODE)'
echo
echo "Done. Values for deploy:"
echo "  PROJECT_ID               = $PROJECT_ID"
echo "  REGION                   = $REGION"
echo "  INSTANCE_CONNECTION_NAME = $(gcloud sql instances describe "$SQL_INSTANCE" --format='value(connectionName)')"
echo "  SERVICE_ACCOUNT          = $RUN_SA_EMAIL"
echo "  KMS_KEY                  = projects/$PROJECT_ID/locations/$REGION/keyRings/$KEYRING/cryptoKeys/$KMS_KEY"
