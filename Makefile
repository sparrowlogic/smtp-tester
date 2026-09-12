# smtp-tester
#
# `make agent-completion` is the single definition of "this change is finished". Everything else in
# this file is either a step of that gate or a convenience for working towards it. CI runs the same
# target, and the Claude Code stop hook in .claude/settings.json runs it too, so there is exactly
# one standard and no way to be done without having met it.

MVN := ./mvnw -B
DOCKER_IMAGE ?= ghcr.io/jackdpeterson/smtp-tester
DOCKER_TAG ?= dev

.DEFAULT_GOAL := help
.PHONY: help agent-completion verify build test fast-test format format-check lint coverage \
        coverage-report deps-check deps-tree audit run docker docker-run clean

help: ## List the available targets
	@grep -hE '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-18s\033[0m %s\n", $$1, $$2}'

## ---------------------------------------------------------------------------
## The gate
## ---------------------------------------------------------------------------

agent-completion: verify ## The completion gate: nothing is done until this exits 0
	@echo ""
	@echo "agent-completion PASSED"
	@echo "  compile + NullAway . clean"
	@echo "  tests .............. see 'Tests run' above"
	@echo "  line coverage ...... >= 85% (enforced by jacoco:check)"
	@echo "  branch coverage .... >= 75% (enforced by jacoco:check)"
	@echo "  spotless ........... clean"
	@echo "  checkstyle ......... 0 violations"

# One Maven invocation rather than a chain of targets: `verify` already runs compile, NullAway,
# the test suite, the jacoco line/branch gate, spotless:check and checkstyle:check in the right
# order, and splitting them would fork the JVM four times to reach the same verdict.
verify: ## Compile, test, and enforce coverage, formatting and style
	$(MVN) verify

## ---------------------------------------------------------------------------
## Parts of the gate, for a faster loop
## ---------------------------------------------------------------------------

build: ## Compile main and test sources only
	$(MVN) -DskipTests -Djacoco.skip=true package

test: ## Run the test suite with coverage
	$(MVN) test

fast-test: ## Run the test suite without the coverage agent
	$(MVN) -Djacoco.skip=true test

format: ## Apply the formatting rules in place
	$(MVN) spotless:apply

format-check: ## Fail if anything is unformatted
	$(MVN) spotless:check

lint: ## Run Checkstyle alone
	$(MVN) checkstyle:check

coverage: ## Run the tests and enforce the coverage floor
	$(MVN) verify -Dspotless.check.skip=true -Dcheckstyle.skip=true

coverage-report: coverage ## Open the HTML coverage report
	@echo "target/site/jacoco/index.html"

## ---------------------------------------------------------------------------
## Supply chain. Deliberately NOT part of agent-completion: both targets reach
## the network, so they would make the gate fail offline and turn a third
## party's release schedule into a red build on an unrelated change.
## ---------------------------------------------------------------------------

deps-check: ## Report newer versions of dependencies, plugins and version properties
	$(MVN) versions:display-dependency-updates versions:display-plugin-updates \
		versions:display-property-updates

deps-tree: ## Print the resolved dependency tree
	$(MVN) dependency:tree

# OSS Index answers anonymous API requests with 401 and the plugin downgrades that to a WARNING,
# so the goal alone exits 0 having audited nothing. The grep turns that silent pass into a failure:
# an audit that cannot reach the database must not look like a clean one.
#
# To make it actually run, add a server to ~/.m2/settings.xml with your OSS Index account
# (https://ossindex.sonatype.org/user/settings -- username as <username>, API token as <password>)
# and pass its id:  make audit OSSINDEX_AUTH_ID=ossindex
OSSINDEX_AUTH_ID ?=
audit: ## Check dependencies against the OSS Index vulnerability database (needs OSSINDEX_AUTH_ID)
	@set -o pipefail; \
	$(MVN) org.sonatype.ossindex.maven:ossindex-maven-plugin:3.2.0:audit \
		$(if $(OSSINDEX_AUTH_ID),-Dossindex.authId=$(OSSINDEX_AUTH_ID),) 2>&1 \
		| tee /tmp/ossindex-audit.log; \
	if grep -q "Failed to fetch component-reports" /tmp/ossindex-audit.log; then \
		echo ""; \
		echo "audit FAILED: could not reach OSS Index, so nothing was actually checked."; \
		echo "Set OSSINDEX_AUTH_ID to a server id in ~/.m2/settings.xml (see the Makefile)."; \
		exit 1; \
	fi

## ---------------------------------------------------------------------------
## Running it
## ---------------------------------------------------------------------------

run: ## Run the server locally on 1025/8025
	$(MVN) spring-boot:run

docker: ## Build the container image
	docker build -t $(DOCKER_IMAGE):$(DOCKER_TAG) .

docker-run: docker ## Build and run the image, bound to loopback only
	docker run --rm -p 127.0.0.1:1025:1025 -p 127.0.0.1:8025:8025 $(DOCKER_IMAGE):$(DOCKER_TAG)

clean: ## Remove build output
	$(MVN) clean
