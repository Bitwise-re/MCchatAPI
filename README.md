Server sided mod offering an API to use the IG chat

Created for a [bitwise.re](https://www.bitwise.re) [feature](https://github.com/Bitwise-re/rpi_apache/issues/19).

## Development environment

The project targets **Minecraft 26.1.2** with **Fabric**. Minecraft 26.1 and later are
unobfuscated, so no Yarn or Mojang mappings dependency is used.

| Tool | Version |
| --- | --- |
| Minecraft | 26.1.2 |
| Fabric Loader | 0.20.1 |
| Fabric API | 0.155.3+26.1.2 |
| Fabric Loom | 1.19.2 |
| Gradle | 9.8.2 |
| Java | 25 |

### Requirements

- JDK 25
- Git
- GNU Make (optional, for the shortcut targets)

On Arch Linux:

```sh
sudo pacman -S --needed jdk25-openjdk
```

The build declares a Java 25 toolchain. The `Makefile` points `JAVA_HOME` at
`/usr/lib/jvm/java-25-openjdk` when it is installed, so Gradle and the dev server run
on JDK 25 without changing the system-wide default Java.

### Commands

```sh
make build      # build the mod jar
make run        # run a local development server
make sources    # decompile Minecraft sources for reference
make clean      # delete Gradle build output
make doctor     # show the active Java and Gradle versions
```

Without Make:

```sh
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew build
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew runServer
```

The built jar is written to `build/libs/mcchatapi-<version>.jar`.

The local development server uses the `run/` directory. It is ignored by git and
`make run` creates `run/eula.txt` and `run/server.properties` (`eula=true`,
`online-mode=false`) on first use.
