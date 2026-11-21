# Prefer JDK 25 when it is installed, without changing the system default.
JDK25 ?= /usr/lib/jvm/java-25-openjdk
ifneq ($(wildcard $(JDK25)),)
JAVA_HOME := $(JDK25)
export JAVA_HOME
endif

.PHONY: build run setup-run smoke sources clean doctor debug

# `make run DEBUG=1` and `make debug` both enable dev-server debug logging.
RUN_LOG_LEVEL := $(if $(filter 1,$(DEBUG)),debug,$(if $(filter debug,$(MAKECMDGOALS)),debug,info))

# `make smoke` uses an isolated server directory so the normal run/ is untouched.
SMOKE_RUN_DIR ?= smoke-run
SMOKE_LOG ?= smoke-server.log
SMOKE_RCON_PORT ?= 25575
SMOKE_RCON_PASSWORD ?= smoke

build:
	./gradlew build

# Make `make run` usable from a fresh clone: the files are git-ignored.
setup-run:
	@mkdir -p run
	@test -f run/eula.txt || printf 'eula=true\n' > run/eula.txt
	@test -f run/server.properties || printf 'online-mode=false\nserver-port=25565\nmotd=MC Chat API dev server\n' > run/server.properties

run: setup-run
	./gradlew runServer -PmcChatApiLogLevel=$(RUN_LOG_LEVEL)

debug: run

smoke: build
	SMOKE_RUN_DIR=$(SMOKE_RUN_DIR) SMOKE_LOG=$(SMOKE_LOG) \
	RCON_PORT=$(SMOKE_RCON_PORT) RCON_PASSWORD=$(SMOKE_RCON_PASSWORD) \
	bash scripts/run-smoke.sh

sources:
	./gradlew genSources

clean:
	./gradlew clean

doctor:
	@echo "JAVA_HOME=$$JAVA_HOME"
	@if [ -n "$$JAVA_HOME" ]; then "$$JAVA_HOME/bin/java" -version; else java -version; fi
	./gradlew --version
