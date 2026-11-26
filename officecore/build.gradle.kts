// Vendored from kejing-dev/SpatialOffice `core` (see README.md): pure Kotlin/JVM .pptx / .docx reading and comment
// writing. No Android imports, no third-party runtime dependencies; unit-tested on the JVM.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    testImplementation(libs.junit)
}
