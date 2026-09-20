variable "region" {
  description = "AWS region to deploy into."
  type        = string
  default     = "us-west-2"
}

variable "environment" {
  description = "Environment name; used in resource names and tags."
  type        = string
  default     = "dev"

  validation {
    condition     = can(regex("^[a-z0-9-]{2,12}$", var.environment))
    error_message = "environment must be 2-12 lowercase alphanumeric or dash characters."
  }
}

variable "vpc_cidr" {
  description = "CIDR block for the VPC."
  type        = string
  default     = "10.42.0.0/16"
}

variable "availability_zone_count" {
  description = "Number of availability zones. Two is the ALB minimum."
  type        = number
  default     = 2

  validation {
    condition     = var.availability_zone_count >= 2 && var.availability_zone_count <= 3
    error_message = "An Application Load Balancer needs at least two AZs; three is the practical maximum here."
  }
}

variable "image_tag" {
  description = "Container image tag to deploy. CI passes the short commit sha."
  type        = string
  default     = "latest"
}

variable "app_replica_count" {
  description = "Number of application tasks. The load tests in load/ assume three."
  type        = number
  default     = 3
}

variable "app_cpu" {
  description = "Fargate CPU units per application task. 2048 = 2 vCPU, the reference sizing."
  type        = number
  default     = 2048
}

variable "app_memory" {
  description = "Fargate memory (MiB) per application task."
  type        = number
  default     = 4096
}

variable "db_instance_class" {
  description = "RDS instance class. db.t4g.medium is the smallest that keeps the pool saturated rather than the CPU."
  type        = string
  default     = "db.t4g.medium"
}

variable "db_allocated_storage" {
  description = "RDS allocated storage in GiB."
  type        = number
  default     = 50
}

variable "redis_node_type" {
  description = "ElastiCache node type."
  type        = string
  default     = "cache.t4g.small"
}

variable "log_retention_days" {
  description = "CloudWatch log retention. Short on purpose: this is a demonstration stack and logs cost money."
  type        = number
  default     = 7
}

variable "allowed_ingress_cidrs" {
  description = "CIDR blocks allowed to reach the load balancer. Default is open, because the demo is public."
  type        = list(string)
  default     = ["0.0.0.0/0"]
}
