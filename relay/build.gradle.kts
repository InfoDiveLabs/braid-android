// The proxy itself, as plain JVM Kotlin with no Android dependencies, so it
// can be tested on the desktop. The app supplies how sockets reach a network.
apply(plugin = "org.jetbrains.kotlin.jvm")

extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
    jvmToolchain(17)
}

dependencies {
    "testImplementation"("junit:junit:4.13.2")
}
