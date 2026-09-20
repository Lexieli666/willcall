#!/usr/bin/env bash
# Run the invariant checks against the deployed database. RDS is in a private subnet, so the
# checks are executed inside a running application task via ECS Exec rather than by opening the
# database to the internet.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT/infra/terraform"

CLUSTER="$(terraform output -raw ecs_cluster_name)"
SERVICE="$(terraform output -raw ecs_app_service_name)"
cd "$REPO_ROOT"

TASK_ARN="$(aws ecs list-tasks --cluster "$CLUSTER" --service-name "$SERVICE" \
  --desired-status RUNNING --query 'taskArns[0]' --output text)"

if [ "$TASK_ARN" = "None" ] || [ -z "$TASK_ARN" ]; then
  printf 'no running task in %s/%s\n' "$CLUSTER" "$SERVICE" >&2
  exit 1
fi

# The application exposes the same SQL as an admin endpoint so the checks do not need a psql
# client inside the runtime image.
aws ecs execute-command \
  --cluster "$CLUSTER" \
  --task "$TASK_ARN" \
  --container app \
  --interactive \
  --command "curl -fsS -X POST http://localhost:8080/api/admin/verify-invariants"
