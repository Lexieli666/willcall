# ADR 0004: Run every measured result on local Docker Compose until AWS credentials exist

- **Status:** accepted
- **Date:** 2026-09-20
- **Deciders:** Lexie Li

## Context

The plan calls for a small AWS stack — ALB, ECS or EC2, RDS PostgreSQL, ElastiCache Redis —
with results measured against it. At the time this build ran, `aws sts get-caller-identity`
failed with `NoCredentials`: no credentials were configured on the build host and none could be
obtained without a person present.

Two bad options presented themselves: stop and produce nothing measurable, or produce numbers
and describe them as cloud numbers. The second is worse than the first.

## Decision

1. The Terraform for the AWS stack is written in full under `infra/terraform/` and is held to
   `terraform fmt -check` and `terraform validate` in CI. `terraform plan` is not run, because
   a plan requires credentials; this is recorded rather than worked around.
2. Every load test runs against the local Docker Compose stack in three-replica mode behind the
   nginx edge proxy, with CPU and memory limits set to the reference sizing so the shape is
   comparable.
3. Every result file and every number derived from one carries the label **"local Docker
   Compose, not AWS"** together with the host's CPU and RAM and the per-container limits.
4. The AWS deployment, the game day against a deployed service, and the public demo with real
   users are listed as pending in `PROGRESS.md`. They are not simulated and not claimed.

## Alternatives considered

- **Skip the Terraform.** Rejected: the infrastructure design is part of the work, and
  `validate` still catches real errors in it.
- **Use LocalStack to fake AWS.** Rejected for measurement: a LocalStack ALB does not have the
  latency, connection handling, or failure behaviour of a real one, so a number measured
  against it would be a number about LocalStack presented as a number about AWS.
- **Run the game day against Docker Compose and call it a game day.** Partially adopted, and
  labelled as such. Killing a replica, exhausting the connection pool, restarting Redis, and
  injecting latency are all reproducible locally and the postmortems are real findings about
  the software. What local Compose cannot exercise is ALB behaviour, cross-AZ failure, and RDS
  failover, so those scenarios stay pending.

## Consequences

- Latency percentiles are measured with the load generator on the same host as the service.
  Network latency between generator and service is therefore near zero, which flatters response
  times and penalises nothing else. Every result file states this.
- CPU contention between the k6 process and the application is real on a single host. The load
  scripts pin k6 and record the host's total core count so the contention is visible.
- When credentials arrive, the load scripts take a base URL and need no change to run against
  the deployed stack.

## Falsifier

`scripts/run-load.sh` writes `run-context.md` into every results directory from the live
environment — it reads the container limits out of Docker and the target out of the base URL it
was given. If a result were ever produced against something other than what its context claims,
the context file would have to be edited by hand, and `load/RESULTS_SUMMARY.md` cross-checks
every published number against the raw file it came from.
