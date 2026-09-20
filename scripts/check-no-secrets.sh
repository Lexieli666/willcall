#!/usr/bin/env bash
# Fail the build if anything that must never be committed has been committed. Cheap to run,
# and the one check whose absence is only noticed after the damage is public.
set -uo pipefail

failures=0

fail() { printf 'FAIL: %s\n' "$1" >&2; failures=$((failures + 1)); }

# Terraform state carries resource identifiers and sometimes secrets in plain text.
if git ls-files | grep -E '\.tfstate($|\.)' ; then
  fail "Terraform state is tracked"
fi

# .tfvars can hold database passwords; only the example is allowed.
if git ls-files | grep -E '\.tfvars$' | grep -v '\.example$' ; then
  fail "a .tfvars file is tracked"
fi

if git ls-files | grep -E '(^|/)\.env($|\.)' | grep -v '\.example$' ; then
  fail "a .env file is tracked"
fi

# Only the project's own root entries are tracked. A local tool that writes its settings into
# the repository root must not end up in the history, and the general rule is cheaper to
# maintain than a list of every tool that might do it.
ALLOWED_ROOT='^(README\.md|CHANGELOG\.md|CONTRIBUTING\.md|PROGRESS\.md|LICENSE|Makefile|docker-compose\.yml|\.gitignore|\.github/|server/|web/|load/|infra/|docs/|scripts/)'
if git ls-files | grep -vE "$ALLOWED_ROOT" ; then
  fail "an unexpected root entry is tracked (see the list in this script)"
fi

if git ls-files | grep -E '\.(pem|p12|pfx)$|(^|/)id_rsa$' ; then
  fail "a private key is tracked"
fi

# AWS access key ids have a fixed, greppable shape.
if git grep -InE '\bAKIA[0-9A-Z]{16}\b' -- . ':(exclude)scripts/check-no-secrets.sh' ; then
  fail "an AWS access key id appears in the tree"
fi

if git grep -InE 'aws_secret_access_key\s*=\s*["'"'"']?[A-Za-z0-9/+=]{40}' -- . ':(exclude)scripts/check-no-secrets.sh' ; then
  fail "an AWS secret access key appears in the tree"
fi

if [ "$failures" -gt 0 ]; then
  printf '\n%s hygiene check(s) failed\n' "$failures" >&2
  exit 1
fi

printf 'hygiene checks passed\n'
