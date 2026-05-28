// Pure Kotlin/JVM module that produces the bytecode for HelperDaemon.
// Consumed by app/build.gradle.kts (BuildHelperDexTask) which runs d8 over
// this jar + kotlin-stdlib to emit app/src/main/assets/helper.dex.
//
// Android classes (IBinder, Parcel, ServiceManager, Process) live on the
// device runtime — declared compileOnly so they resolve at compile time but
// stay out of the output jar (and out of the dex bundle).

import java.util.Properties

plugins {
    kotlin("jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

val androidSdkDir: String = run {
    System.getenv("ANDROID_HOME")?.let { return@run it }
    val local = rootProject.file("local.properties")
    require(local.exists()) { "ANDROID_HOME not set and local.properties missing" }
    val props = Properties().apply { local.inputStream().use { load(it) } }
    requireNotNull(props.getProperty("sdk.dir")) { "sdk.dir missing from local.properties" }
}

val androidJar = file("$androidSdkDir/platforms/android-34/android.jar")

dependencies {
    compileOnly(files(androidJar))
    // org.json ships with Android (android.jar) — no implementation dep needed.
}
