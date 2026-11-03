plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin { jvmToolchain(17) }

dependencies {
    api("com.google.crypto.tink:tink:1.15.0")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    // Parity fixtures and the shipped policy live outside the module.
    systemProperty("crisislink.root", rootProject.projectDir.absolutePath)
}
