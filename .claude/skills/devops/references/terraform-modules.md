# Terraform Module Patterns — AWS

## Table of Contents
1. [EKS Cluster](#eks-cluster)
2. [RDS Aurora PostgreSQL](#rds-aurora-postgresql)
3. [ElastiCache Redis](#elasticache-redis)
4. [VPC baseline](#vpc-baseline)

---

## EKS Cluster

```hcl
# environments/prod/main.tf
module "eks" {
  source  = "terraform-aws-modules/eks/aws"
  version = "~> 20.0"

  cluster_name    = "my-cluster-prod"
  cluster_version = "1.30"

  vpc_id     = module.vpc.vpc_id
  subnet_ids = module.vpc.private_subnets

  # Private endpoint — no public API server
  cluster_endpoint_public_access  = false
  cluster_endpoint_private_access = true

  # Managed node group — let AWS handle OS patching
  eks_managed_node_groups = {
    general = {
      instance_types = ["m6i.xlarge"]
      min_size       = 3
      max_size       = 10
      desired_size   = 3

      # Spread across AZs
      subnet_ids = module.vpc.private_subnets

      labels = {
        role = "general"
      }

      taints = []
    }
  }

  # Enable IRSA
  enable_irsa = true

  # Add-ons
  cluster_addons = {
    coredns                = { most_recent = true }
    kube-proxy             = { most_recent = true }
    vpc-cni                = { most_recent = true }
    aws-ebs-csi-driver     = { most_recent = true }
    aws-load-balancer-controller = { most_recent = true }
  }

  tags = local.common_tags
}
```

---

## RDS Aurora PostgreSQL

```hcl
module "rds" {
  source  = "terraform-aws-modules/rds-aurora/aws"
  version = "~> 9.0"

  name           = "my-service-prod"
  engine         = "aurora-postgresql"
  engine_version = "16.2"
  instance_class = "db.r6g.large"

  instances = {
    writer = {}       # writer instance
    reader = {}       # reader instance in different AZ
  }

  vpc_id               = module.vpc.vpc_id
  db_subnet_group_name = module.vpc.database_subnet_group_name

  # Allow only from EKS node security group
  security_group_rules = {
    eks_ingress = {
      source_security_group_id = module.eks.node_security_group_id
    }
  }

  # Credentials in Secrets Manager (auto-rotated)
  manage_master_user_password = true

  # Backups and maintenance
  backup_retention_period      = 7
  preferred_backup_window      = "03:00-04:00"
  preferred_maintenance_window = "sun:05:00-sun:06:00"

  # Deletion protection — never allow accidental destroy in prod
  deletion_protection = true
  skip_final_snapshot = false

  tags = local.common_tags
}

# Output: app reads this from Secrets Manager at runtime via IRSA
output "rds_secret_arn" {
  value = module.rds.cluster_master_user_secret[0].secret_arn
}
```

---

## ElastiCache Redis

```hcl
module "elasticache" {
  source  = "terraform-aws-modules/elasticache/aws"
  version = "~> 1.0"

  cluster_id         = "my-service-prod"
  engine             = "redis"
  engine_version     = "7.2"
  node_type          = "cache.r6g.large"
  num_cache_clusters = 2    # primary + replica

  subnet_ids = module.vpc.elasticache_subnets

  # Allow only from EKS
  security_group_ids = [aws_security_group.redis_from_eks.id]

  # Encryption in transit and at rest
  transit_encryption_enabled = true
  at_rest_encryption_enabled = true

  # Auth token stored in Secrets Manager
  auth_token = random_password.redis_auth.result

  automatic_failover_enabled = true
  multi_az_enabled           = true

  tags = local.common_tags
}

resource "random_password" "redis_auth" {
  length  = 32
  special = false
}

resource "aws_secretsmanager_secret_version" "redis_auth" {
  secret_id = aws_secretsmanager_secret.redis_auth.id
  secret_string = jsonencode({
    auth_token = random_password.redis_auth.result
    host       = module.elasticache.replication_group_primary_endpoint_address
    port       = 6379
  })
}
```

---

## VPC Baseline

```hcl
module "vpc" {
  source  = "terraform-aws-modules/vpc/aws"
  version = "~> 5.0"

  name = "my-vpc-prod"
  cidr = "10.0.0.0/16"

  azs              = ["ap-southeast-1a", "ap-southeast-1b", "ap-southeast-1c"]
  private_subnets  = ["10.0.1.0/24", "10.0.2.0/24", "10.0.3.0/24"]
  public_subnets   = ["10.0.101.0/24", "10.0.102.0/24", "10.0.103.0/24"]
  database_subnets = ["10.0.201.0/24", "10.0.202.0/24", "10.0.203.0/24"]

  # NAT Gateway — one per AZ for HA (costs more but survives AZ failure)
  enable_nat_gateway     = true
  single_nat_gateway     = false
  one_nat_gateway_per_az = true

  # Required tags for EKS ALB controller to discover subnets
  public_subnet_tags = {
    "kubernetes.io/role/elb" = 1
  }
  private_subnet_tags = {
    "kubernetes.io/role/internal-elb" = 1
  }

  # VPC Flow Logs — required for security compliance
  enable_flow_log                      = true
  create_flow_log_cloudwatch_log_group = true
  create_flow_log_cloudwatch_iam_role  = true

  tags = local.common_tags
}

locals {
  common_tags = {
    environment = "prod"
    team        = "platform"
    managed-by  = "terraform"
    project     = "my-service"
  }
}
```
