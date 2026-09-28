import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Codec FCOD1 en Kotlin puro (sin dependencias de Android): se prueba en la JVM
// contra la herramienta de Python y lo usa la app.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    testImplementation(libs.junit)
}

tasks.test {
    // Raíz del repo (donde vive fotocodec.py) para las pruebas cruzadas con Python.
    systemProperty("kyoto.repoRoot", rootDir.parentFile.absolutePath)
    // Temporales en build/ (no en C:), se borran con `clean`.
    val tmp = layout.buildDirectory.dir("tmp/test-files").get().asFile
    systemProperty("java.io.tmpdir", tmp.absolutePath)
    doFirst { tmp.deleteRecursively(); tmp.mkdirs() }
    System.getenv("KYOTO_PYTHON")?.let { systemProperty("kyoto.python", it) }
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
