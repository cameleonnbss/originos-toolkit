# OriginOS Toolkit — developer shortcuts.
#
# These are convenience wrappers around exactly the commands CI runs. Nothing
# here is required: every target is a one-liner you can copy into a terminal.

SHELL := /bin/sh
PYTHON ?= python
GRADLE ?= gradle
CATALOG := catalog

.DEFAULT_GOAL := help

.PHONY: help
help: ## Show this help
	@grep -hE '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-18s\033[0m %s\n", $$1, $$2}'

.PHONY: lint
lint: ## Validate the catalog (roles, risk levels, inverses, curated links)
	PYTHONPATH=cli $(PYTHON) -m originos_toolkit.cli lint --catalog $(CATALOG)

.PHONY: test-cli
test-cli: ## Run the Python test suite
	cd cli && $(PYTHON) -m unittest discover -s tests -t . -v

.PHONY: test-android
test-android: ## Run the Kotlin JVM tests
	$(GRADLE) testDebugUnitTest

.PHONY: test
test: lint test-cli test-android ## Run every test that CI runs

.PHONY: docs
docs: ## Regenerate the catalog-derived documentation
	$(PYTHON) scripts/generate_docs.py

.PHONY: docs-check
docs-check: ## Fail if the generated documentation is stale
	$(PYTHON) scripts/generate_docs.py --check

.PHONY: catalog
catalog: ## List every tweak
	PYTHONPATH=cli $(PYTHON) -m originos_toolkit.cli catalog --catalog $(CATALOG)

.PHONY: doctor
doctor: ## Check adb, the attached device and Shizuku
	PYTHONPATH=cli $(PYTHON) -m originos_toolkit.cli doctor

.PHONY: export-gaming
export-gaming: ## Write an ADB script for the gaming-max profile
	PYTHONPATH=cli $(PYTHON) -m originos_toolkit.cli export gaming-max \
		--catalog $(CATALOG) --format sh -o apply-gaming.sh
	@echo "wrote apply-gaming.sh"

.PHONY: build
build: ## Assemble debug and release APKs
	$(GRADLE) assembleDebug assembleRelease

.PHONY: apk
apk: ## Assemble the debug APK and print its path
	$(GRADLE) assembleDebug
	@ls -lh app/build/outputs/apk/debug/*.apk

.PHONY: wrapper
wrapper: ## Generate a local Gradle wrapper (the jar is not committed)
	$(GRADLE) wrapper --gradle-version 8.9

.PHONY: verify
verify: lint docs-check test-cli ## Everything that must pass before a pull request
	@echo "catalog, docs and CLI are in good shape"

.PHONY: clean
clean: ## Remove build output
	$(GRADLE) clean
	rm -rf cli/dist cli/build cli/*.egg-info
	find . -name '__pycache__' -type d -prune -exec rm -rf {} +
