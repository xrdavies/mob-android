# Android app tasks. Run `make` to list them.
GRADLE ?= ./gradlew
.DEFAULT_GOAL := help
.PHONY: help test apk release release-test

help: ## List targets
	@awk 'BEGIN {FS = ":.*## "} /^[a-zA-Z_-]+:.*## / {printf "  %-14s %s\n", $$1, $$2}' $(MAKEFILE_LIST)

test: ## Run unit tests
	$(GRADLE) testDebugUnitTest

apk: ## Build a debug APK
	$(GRADLE) assembleDebug

release: export RELEASE_TAG = $(VERSION)
release: ## Create and push a release tag (VERSION=vX.Y.Z)
	node .github/release/tag.mjs "$$RELEASE_TAG"

release-test: ## Test release tagging and R2 publishing
	node --test .github/release/*.test.mjs
