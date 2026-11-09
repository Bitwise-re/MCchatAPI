# Prefer JDK 25 when it is installed, without changing the system default.
JDK25 ?= /usr/lib/jvm/java-25-openjdk
ifneq ($(wildcard $(JDK25)),)
JAVA_HOME := $(JDK25)
export JAVA_HOME
endif

.PHONY: build run setup-run sources clean doctor

build:
	./gradlew build

# Make `make run` usable from a fresh clone: the files are git-ignored.
setup-run:
	@mkdir -p run
	@test -f run/eula.txt || printf 'eula=true\n' > run/eula.txt
	@test -f run/server.properties || printf 'online-mode=false\nserver-port=25565\nmotd=MC Chat API dev server\n' > run/server.properties

run: setup-run
	./gradlew runServer

sources:
	./gradlew genSources

clean:
	./gradlew clean

doctor:
	@echo "JAVA_HOME=$$JAVA_HOME"
	@if [ -n "$$JAVA_HOME" ]; then "$$JAVA_HOME/bin/java" -version; else java -version; fi
	./gradlew --version
