#!/usr/bin/env bash
# Deploy the current commit. CI runs this on merge to main; it is also the command a human
# runs by hand, so that the two paths cannot drift.
#
# Without AWS credentials it refuses clearly instead of half-working: see
# docs/adr/0004-run-on-local-docker-compose-until-aws-credentials-exist.md.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

ENVIRONMENT="${WILLCALL_ENV:-dev}"
TAG="$(git rev-parse --short HEAD)"

if ! aws sts get-caller-identity >/dev/null 2>&1; then
  cat >&2 <<'MSG'
No usable AWS credentials.

Nothing was deployed. To run the stack locally instead:

    make up          # three replicas behind the local edge proxy
    open http://127.0.0.1:8080

To deploy for real, configure credentials and re-run. The Terraform is complete and passes
`terraform validate`; what it has never had is an account to run against.
MSG
  exit 2
fi

REGION="$(aws configure get region || echo us-west-2)"
ACCOUNT="$(aws sts get-caller-identity --query Account --output text)"
REGISTRY="${ACCOUNT}.dkr.ecr.${REGION}.amazonaws.com"

printf 'deploying %s to %s in %s\n' "$TAG" "$ENVIRONMENT" "$REGION"

aws ecr get-login-password --region "$REGION" | docker login --username AWS --password-stdin "$REGISTRY"

docker build --build-arg GIT_COMMIT="$TAG" -t "$REGISTRY/willcall-server:$TAG" server
docker build -t "$REGISTRY/willcall-web:$TAG" web
docker push "$REGISTRY/willcall-server:$TAG"
docker push "$REGISTRY/willcall-web:$TAG"

cd infra/terraform
terraform init -input=false
terraform apply -input=false -auto-approve \
  -var-file="env/${ENVIRONMENT}.tfvars" \
  -var "image_tag=$TAG"

URL="$(terraform output -raw public_url)"
cd "$REPO_ROOT"

./scripts/wait-for-healthy.sh "$URL" 300
curl -fsS "$URL/" | grep -q '<div id="root">'

printf '\ndeployed: %s\n' "$URL"
printf 'tear down with: cd infra/terraform && terraform destroy -var-file=env/%s.tfvars -auto-approve\n' "$ENVIRONMENT"
