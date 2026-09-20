output "public_url" {
  description = "Public URL of the deployment. CI smoke-tests this before calling a deploy done."
  value       = "http://${aws_lb.main.dns_name}"
}

output "alb_dns_name" {
  value = aws_lb.main.dns_name
}

output "ecs_cluster_name" {
  value = aws_ecs_cluster.main.name
}

output "ecs_app_service_name" {
  value = aws_ecs_service.app.name
}

output "db_endpoint" {
  description = "RDS endpoint. Private; reachable only from an application task."
  value       = aws_db_instance.main.address
}

output "redis_endpoint" {
  value = aws_elasticache_replication_group.main.primary_endpoint_address
}

output "server_image_repository" {
  value = aws_ecr_repository.server.repository_url
}

output "web_image_repository" {
  value = aws_ecr_repository.web.repository_url
}

output "destroy_command" {
  description = "The exact command that tears this stack down."
  value       = "cd infra/terraform && terraform destroy -var-file=env/${var.environment}.tfvars -auto-approve"
}
