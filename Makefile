# Targets are ordered by how long they take. The fast ones are the ones you run.

JAVA_SRC   := src/main/java/io/realmledger
SELFCHECK  := tools/core-selfcheck/SelfCheck.java
OUT        := target/selfcheck

.PHONY: help core-check spec-gate layering test test-docker verify clean

help:
	@echo "core-check   dependency-free domain check, ~1s, no Maven or Docker"
	@echo "layering     architecture guard (also runs as a PostToolUse hook)"
	@echo "spec-gate    acceptance criteria vs @SpecRef annotations"
	@echo "test         full Maven suite (Docker-tagged tests excluded)"
	@echo "test-docker  Testcontainers tests; needs a running Docker daemon"
	@echo "verify       everything CI runs"

## Compile and run the pure core with plain javac. This is the agent's inner loop: it
## needs no network, no Maven repository and no Docker, so it works on a fresh clone and
## it works in about a second.
core-check:
	@mkdir -p $(OUT)
	@javac -d $(OUT) -Xlint:all \
		$(shell find $(JAVA_SRC)/core $(JAVA_SRC)/port $(JAVA_SRC)/application -name '*.java') \
		$(SELFCHECK)
	@java -cp $(OUT) SelfCheck

layering:
	@tools/hooks/check_layering.sh && echo "layering OK"

spec-gate:
	@python3 tools/spec_gate.py

test:
	@mvn -q -B test

## Excluded from `test` so the fast suite runs on a laptop with Docker stopped.
test-docker:
	@mvn -q -B test -DexcludedGroups= -Dgroups=docker

verify: layering core-check spec-gate test

clean:
	@rm -rf target
