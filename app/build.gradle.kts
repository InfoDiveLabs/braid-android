import java.util.Properties

apply(plugin = "com.android.application")
apply(plugin = "org.jetbrains.kotlin.android")

/** Signing comes from the environment in CI, or from the git-ignored .env at the root on a developer's machine. */
val signing = Properties().apply {
    rootProject.file(".env").takeIf { it.exists() }?.reader()?.use { load(it) }
    System.getenv().filterKeys { it.startsWith("BRAID_") }.forEach { (k, v) -> setProperty(k, v) }
}
val appVersion = "0.1.1"

extensions.configure<com.android.build.api.dsl.ApplicationExtension> {
    namespace = "com.infodive.braid.companion"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.infodive.braid.companion"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = appVersion
    }

    signingConfigs {
        create("release") {
            signing.getProperty("BRAID_KEYSTORE")?.let { path ->
                storeFile = rootProject.file(path)
                storePassword = signing.getProperty("BRAID_KEYSTORE_PASSWORD")
                keyAlias = signing.getProperty("BRAID_KEY_ALIAS")
                keyPassword = signing.getProperty("BRAID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            if (signing.getProperty("BRAID_KEYSTORE") != null) signingConfig = signingConfigs.getByName("release")
        }
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
    "implementation"("com.google.android.gms:play-services-code-scanner:16.1.0")
}

/** One APK for every architecture: the app has no native code, so per-ABI builds would be identical. */
tasks.register<Copy>("dist") {
    dependsOn("assembleRelease")
    from(layout.buildDirectory.dir("outputs/apk/release")) { include("*-release.apk") }
    into(rootProject.layout.projectDirectory.dir("dist"))
    rename { "braid-android-$appVersion.apk" }
}
