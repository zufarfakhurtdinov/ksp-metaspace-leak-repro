import java.lang.management.ManagementFactory

buildscript {
  dependencies { classpath(files("generation")) }
}

plugins {
  kotlin("jvm") version "2.4.21"
  id("com.google.devtools.ksp") version "2.3.14"
}

dependencies { ksp(project(":processor")) }

abstract class Report : DefaultTask() {
  @TaskAction
  fun report() {
    val metaspacePool = ManagementFactory.getMemoryPoolMXBeans().first { it.name == "Metaspace" }
    val metaspaceMb = metaspacePool.usage.used / (1024 * 1024)

    val allThreads = Thread.getAllStackTraces().keys
    val periodicThreads = allThreads.filter { it.name == "Periodic tasks thread" }

    println("metaspace: $metaspaceMb MB, Periodic tasks threads: ${periodicThreads.size}")
  }
}

tasks.register<Report>("report") {
  description = "Prints metaspace use and the number of KSP threads in the Gradle daemon."
  mustRunAfter("kspKotlin")
}
