# KSP2 leaks plugin classloaders in the Gradle daemon

Minimal reproduction for a KSP2 metaspace leak. Seen on KSP 2.3.6 and 2.3.14, and still present on `main`
(`ac94ad13`). Kotlin 2.4.21, Gradle 9.9.2, JDK 21 and 25.

## Problem

A KSP2 run in the Gradle daemon starts threads in the KSP engine classloader that never stop:

- the IntelliJ `Periodic tasks thread` (`ksp.com.intellij.util.concurrency.AppDelayQueue$TransferThread`);
- the embedded kotlinx.coroutines `DefaultDispatcher-worker-*` threads.

These threads keep the Gradle classloader that loaded the KSP Gradle plugin reachable: the periodic thread through its
context classloader, the coroutine workers through their inherited access control context.

Each time Gradle loads the KSP Gradle plugin in a new classloader, the previous classloader leaks, together with the
plugin classes and the Analysis API classes it loaded. Gradle does this when the build script classpath of a project
that applies KSP changes, for example after:

- an edit to the code of an included build that provides plugins, such as `build-logic`;
- adding, removing or upgrading another plugin in the same `plugins {}` block;
- a change to a `buildscript` dependency. This project uses this case.

Switching to a branch where any of these differs has the same effect, and so does switching back. Editing a build
script without changing its classpath does not leak. Metaspace grows with each change until the daemon fails with
`OutOfMemoryError: Metaspace`.

Path from a GC root, from a heap dump of this project:

```
ksp.com.intellij.util.concurrency.AppDelayQueue$TransferThread   [GC root: thread]
  .contextClassLoader -> VisitableURLClassLoader$InstrumentingVisitableURLClassLoader   (old plugin classloader)
    .classes -> class com.google.devtools.ksp.gradle.KspAATask
```

## Project

- The root project applies Kotlin JVM and KSP and runs the processor on one empty class. KSP skips the task without
  sources. `generation` is on its build script classpath, so changing the file in it gives the project a new plugin
  classloader.
- `processor`: a no-op `SymbolProcessorProvider`. KSP skips the task without a processor.
- `report`: prints the daemon's metaspace use and the number of `Periodic tasks thread` threads.
- `repro.sh`: runs `kspKotlin --rerun report` repeatedly in one daemon.
- `gradle.properties`: limits metaspace to 512 MB, so the leak fails the build after a few changes, and writes a heap
  dump when it does.

## Run

```
./gradlew --stop    # stop idle Gradle 9.9.2 daemons, so the runs start in a new daemon
./repro.sh 3 same   # control: rerun kspKotlin without changes
./gradlew --stop
./repro.sh          # change the build script classpath before each of 20 kspKotlin reruns
```

An existing daemon still works, but starts with more metaspace in use and fails earlier.

Results from a fresh clone on macOS 15.8.

Corretto 21.0.13.1:

```
$ ./repro.sh 3 same
build 1: metaspace: 181 MB, Periodic tasks threads: 1
build 2: metaspace: 182 MB, Periodic tasks threads: 1
build 3: metaspace: 182 MB, Periodic tasks threads: 1

$ ./repro.sh
build 1: metaspace: 106 MB, Periodic tasks threads: 1
build 2: metaspace: 146 MB, Periodic tasks threads: 2
build 3: metaspace: 185 MB, Periodic tasks threads: 3
build 4: metaspace: 224 MB, Periodic tasks threads: 4
build 5: metaspace: 263 MB, Periodic tasks threads: 5
build 6: metaspace: 303 MB, Periodic tasks threads: 6
build 7: metaspace: 342 MB, Periodic tasks threads: 7
build 8: metaspace: 380 MB, Periodic tasks threads: 8
build 9: metaspace: 419 MB, Periodic tasks threads: 9
build 10: metaspace: 458 MB, Periodic tasks threads: 10
build 11: metaspace: 497 MB, Periodic tasks threads: 11
build 12 failed, full output in build/repro.log:
e: [ksp] java.lang.OutOfMemoryError: Metaspace
```

Corretto 25.0.4.1:

```
$ ./repro.sh 3 same
build 1: metaspace: 177 MB, Periodic tasks threads: 1
build 2: metaspace: 178 MB, Periodic tasks threads: 1
build 3: metaspace: 179 MB, Periodic tasks threads: 1

$ ./repro.sh
build 1: metaspace: 104 MB, Periodic tasks threads: 1
build 2: metaspace: 143 MB, Periodic tasks threads: 2
build 3: metaspace: 181 MB, Periodic tasks threads: 3
build 4: metaspace: 220 MB, Periodic tasks threads: 4
build 5: metaspace: 258 MB, Periodic tasks threads: 5
build 6: metaspace: 297 MB, Periodic tasks threads: 6
build 7: metaspace: 335 MB, Periodic tasks threads: 7
build 8: metaspace: 373 MB, Periodic tasks threads: 8
build 9: metaspace: 411 MB, Periodic tasks threads: 9
build 10: metaspace: 450 MB, Periodic tasks threads: 10
build 11: metaspace: 488 MB, Periodic tasks threads: 11
build 12 failed, full output in build/repro.log:
e: [ksp] java.lang.OutOfMemoryError: Metaspace
```

Each classpath change leaves another set of threads and about 40 MB of metaspace that cannot be reclaimed, until
metaspace runs out. Where it runs out varies, so the failure is reported differently from run to run: as `e: [ksp]
java.lang.OutOfMemoryError: Metaspace` from KSP, as `Metaspace` from Gradle, as a class that failed to initialize, or as
a crashed daemon. The daemon also writes a heap dump (`java_pid*.hprof`) to the project directory. The leak per change
grows with the plugins on the same classpath. It was about 100 MB in a real multi-module build. The control starts
higher because its first build also compiles the build scripts, and their classes are not unloaded until a GC needs the
space.

## Cause

Nothing stops the threads. The KSP Gradle plugin has `IsolatedClassLoaderCacheBuildService`, whose `close()` clears
`IsolatedClassLoaderCache`, but:

- `IsolatedClassLoaderCache.clear()` only closes the cached classloaders. Closing a classloader does not stop threads
  that were started from it.
- Since 9650514d (2.3.12), `KspAATask` never calls `isolatedClassLoaderCacheBuildService.get()`. Gradle creates a build
  service only when it is first used, so the service is never created and `close()` never runs.

## Expected

KSP stops the threads it starts when the build finishes, so old plugin classloaders can be collected after a build
script classpath change.
