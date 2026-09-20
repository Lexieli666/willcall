# RDS PostgreSQL and ElastiCache Redis. Both are single-node: this stack exists to be load
# tested and torn down, and a Multi-AZ RDS would double the bill without changing any number
# the project publishes. The trade-off is stated in docs/capacity-model.md.

resource "random_password" "db" {
  length  = 32
  special = false
}

resource "aws_db_subnet_group" "main" {
  name       = local.name
  subnet_ids = aws_subnet.private[*].id
}

resource "aws_db_parameter_group" "main" {
  name   = "${local.name}-pg16"
  family = "postgres16"

  # The connection-pool exhaustion game day needs a reachable ceiling, and pg_stat_statements
  # is how the query-plan work in Phase 4 finds the hot statements.
  parameter {
    name         = "shared_preload_libraries"
    value        = "pg_stat_statements"
    apply_method = "pending-reboot"
  }

  parameter {
    name  = "log_min_duration_statement"
    value = "500"
  }

  parameter {
    name  = "track_io_timing"
    value = "1"
  }
}

resource "aws_db_instance" "main" {
  identifier     = local.name
  engine         = "postgres"
  engine_version = "16"
  instance_class = var.db_instance_class

  allocated_storage     = var.db_allocated_storage
  max_allocated_storage = var.db_allocated_storage * 2
  storage_type          = "gp3"
  storage_encrypted     = true

  db_name  = "willcall"
  username = "willcall"
  password = random_password.db.result

  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.data.id]
  parameter_group_name   = aws_db_parameter_group.main.name

  multi_az                = false
  publicly_accessible     = false
  backup_retention_period = 1
  skip_final_snapshot     = true
  deletion_protection     = false
  apply_immediately       = true

  performance_insights_enabled    = true
  enabled_cloudwatch_logs_exports = ["postgresql"]

  tags = { Name = local.name }
}

resource "aws_elasticache_subnet_group" "main" {
  name       = local.name
  subnet_ids = aws_subnet.private[*].id
}

resource "aws_elasticache_replication_group" "main" {
  replication_group_id = local.name
  description          = "Willcall waiting room, rate limits and seat-delta pub/sub"

  engine         = "redis"
  engine_version = "7.1"
  node_type      = var.redis_node_type
  port           = 6379

  num_cache_clusters         = 1
  automatic_failover_enabled = false
  multi_az_enabled           = false

  subnet_group_name  = aws_elasticache_subnet_group.main.name
  security_group_ids = [aws_security_group.data.id]

  # Restarting Redis is a game-day scenario, so nothing here may make its loss fatal:
  # persistence is off and the application treats an empty Redis as a cold start.
  snapshot_retention_limit   = 0
  transit_encryption_enabled = false
  apply_immediately          = true

  tags = { Name = local.name }
}

# The database password never appears in the repository or in a task definition; the task
# reads it from Secrets Manager at start-up using its execution role.
resource "aws_secretsmanager_secret" "db_password" {
  name                    = "${local.name}/db-password"
  recovery_window_in_days = 0
}

resource "aws_secretsmanager_secret_version" "db_password" {
  secret_id     = aws_secretsmanager_secret.db_password.id
  secret_string = random_password.db.result
}
