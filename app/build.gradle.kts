apply(plugin = "com.android.application")
apply(plugin = "org.jetbrains.kotlin.android")

extensions.configure<com.android.build.api.dsl.ApplicationExtension> {
    namespace = "com.infodive.braid.companion"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.infodive.braid.companion"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension> {
    jvmToolchain(17)
}

dependencies {
    "implementation"(project(":relay"))
}
