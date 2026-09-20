# Willcall developer entry points. Every target here is also what CI runs, so "it passes
# locally" and "it passes in CI" mean the same thing.

SHELL := /bin/bash
.DEFAULT_GOAL := help

GRADLE       := ./gradlew
SERVER_DIR   := server
WEB_DIR      := web
COMPOSE      := docker compose
GIT_COMMIT   := $(shell git rev-parse --short HEAD 2>/dev/null || echo unknown)
EDGE_PORT    ?= 8080
BASE_URL     ?= http://127.0.0.1:$(EDGE_PORT)

export GIT_COMMIT

.PHONY: help
help: ## Show this help
	@grep -hE '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-22s\033[0m %s\n", $$1, $$2}'

## ---------------------------------------------------------------- build

.PHONY: build
build: ## Compile backend and frontend
	cd $(SERVER_DIR) && $(GRADLE) assemble
	cd $(WEB_DIR) && npm run build

.PHONY: install
install: ## Install frontend dependencies from the lockfile
	cd $(WEB_DIR) && npm ci --no-audit --no-fund

## ---------------------------------------------------------------- test

.PHONY: test
test: ## Fast suites: backend unit + property, frontend unit
	cd $(SERVER_DIR) && $(GRADLE) test
	cd $(WEB_DIR) && npm run test

.PHONY: test-long
test-long: ## Backend suites in long mode (50 concurrency runs, larger property samples)
	cd $(SERVER_DIR) && $(GRADLE) test integrationTest -Dwillcall.longMode=true --rerun-tasks

.PHONY: integration
integration: ## Testcontainers suite (real PostgreSQL and Redis)
	cd $(SERVER_DIR) && $(GRADLE) integrationTest

.PHONY: coverage
coverage: ## Backend coverage report
	cd $(SERVER_DIR) && $(GRADLE) test jacocoTestReport
	@echo "report: $(SERVER_DIR)/build/reports/jacoco/test/html/index.html"

.PHONY: e2e
e2e: ## Playwright end-to-end suite, including axe and keyboard-only passes
	cd $(WEB_DIR) && npx playwright test

.PHONY: lighthouse
lighthouse: ## Lighthouse CI budgets
	./scripts/lighthouse.sh

.PHONY: bench
bench: ## JMH benchmarks (segment tree vs linear scan)
	cd $(SERVER_DIR) && $(GRADLE) jmh

## ---------------------------------------------------------------- quality

.PHONY: lint
lint: ## Static checks: Spotless, Error Prone, ESLint, tsc
	cd $(SERVER_DIR) && $(GRADLE) spotlessCheck compileJava
	cd $(WEB_DIR) && npx tsc -b && npm run lint

.PHONY: format
format: ## Apply formatters
	cd $(SERVER_DIR) && $(GRADLE) spotlessApply
	cd $(WEB_DIR) && npm run format

.PHONY: verify-invariants
verify-invariants: ## Assert the capacity invariant against the running database
	./scripts/verify-invariants.sh

.PHONY: verify
verify: ## Every check this project claims, with a pass/fail line each (needs `make up` first)
	./scripts/run-all-verifications.sh

.PHONY: results
results: ## Regenerate RESULTS_SUMMARY.md and the README table from the raw result files
	./scripts/build-results-summary.sh

## ---------------------------------------------------------------- run

.PHONY: dev
dev: ## Postgres, Redis and one backend replica; frontend on the Vite dev server
	$(COMPOSE) up -d --build postgres redis app1
	@echo "api:  http://127.0.0.1:18081"
	@echo "then: cd web && npm run dev"

.PHONY: up
up: ## Three replicas behind the local edge proxy (the load-test target)
	$(COMPOSE) --profile replicas up -d --build
	./scripts/wait-for-healthy.sh $(BASE_URL)

.PHONY: down
down: ## Stop the stack, keep the volumes
	$(COMPOSE) --profile replicas down

.PHONY: clean
clean: ## Stop the stack and delete the data volumes
	$(COMPOSE) --profile replicas down -v
	cd $(SERVER_DIR) && $(GRADLE) clean
	rm -rf $(WEB_DIR)/dist $(WEB_DIR)/coverage $(WEB_DIR)/playwright-report

.PHONY: logs
logs: ## Follow application logs
	$(COMPOSE) --profile replicas logs -f app1 app2 app3

## ---------------------------------------------------------------- load

.PHONY: load-smoke
load-smoke: ## Two-minute k6 smoke run against the local stack
	./scripts/run-load.sh smoke

.PHONY: load-flash
load-flash: ## Flash-sale scenario: 10,000 virtual users arriving in 10 s
	./scripts/run-load.sh flash

.PHONY: load-sse
load-sse: ## 5,000 long-lived SSE connections
	./scripts/run-sse-load.sh 5000

.PHONY: load-fairness
load-fairness: ## Measure the FIFO inversion rate under a burst
	./scripts/run-fairness.sh

.PHONY: flash-suite
flash-suite: ## The flash sale, 50 times, with the invariant checked after each
	./scripts/run-flash-suite.sh 50

.PHONY: capacity-sweep
capacity-sweep: ## Find the sustainable hold rate by measuring several of them
	./scripts/run-capacity-sweep.sh

.PHONY: diagnose
diagnose: ## Run the rate that breaks while sampling what the replicas say about themselves
	./scripts/diagnose-ceiling.sh

.PHONY: game-day
game-day: ## One failure rehearsal: make game-day SCENARIO=kill-replica
	./scripts/run-game-day.sh $(SCENARIO)

.PHONY: dashboards
dashboards: ## Capture Grafana screenshots with real data on them, into docs/images/
	./scripts/capture-dashboards.sh

.PHONY: seed
seed: ## Seed the large dataset used for query-plan checks
	./scripts/seed-large-dataset.sh

## ---------------------------------------------------------------- deploy

.PHONY: tf-validate
tf-validate: ## terraform fmt -check and validate
	cd infra/terraform && terraform init -backend=false -input=false && terraform fmt -check -recursive && terraform validate

.PHONY: deploy
deploy: ## Deploy the current commit (CI runs this on merge to main)
	./scripts/deploy.sh
