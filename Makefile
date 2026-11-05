# Check harness cho repo cloude (AGENTS.md: chạy `make check` đầu/cuối phiên)
#
# Java: project cần Java 21 (system Java 25 làm Mockito/byte-buddy fail).
# JAVA_HOME được phát hiện tự động trên macOS qua /usr/libexec/java_home;
# máy khác override: make check JAVA_HOME=/path/to/jdk-21
JAVA_HOME ?= $(shell /usr/libexec/java_home -v 21 2>/dev/null || echo "")

.PHONY: check check-openapi check-tests check-whitespace

check: check-openapi check-tests check-whitespace

check-openapi:
	cd be && python3 scripts/validate-openapi.py

check-tests:
	cd be && JAVA_HOME="$(JAVA_HOME)" mvn -q test

check-whitespace:
	git diff --check
