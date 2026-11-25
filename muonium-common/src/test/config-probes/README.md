# Embedded settings probes

These offline probes exercise the shared `configskija` sources. They are not a
mod, a Gradle project, or a production source set. Outputs stay under `build/`.

From this directory, run `./verify.ps1 -Jdk D:/JDK17` for the neutral behavior
suite and `./preview.ps1 -Jdk D:/JDK17` for CPU raster fixtures. For the host
font suite, first run `:fabric:skijaConfigClasspath` in the 26.3 project with
`gradle/host.init.gradle` as an init script, then run
`./src/test/java/com/muoniumplayer/configskija/text/run.ps1 -Jdk D:/JDK25`.
These do not launch the game or validate loader hooks.

The former standalone project's documents, reference snapshots and audit
scripts are retained as historical evidence; their old paths and byte-for-byte
asset comparisons do not describe the current integrated implementation.
