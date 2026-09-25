import java.util.Properties

fun gitValue(vararg args: String): String = runCatching {
    project.providers.exec {
        commandLine("git", *args)
    }.standardOutput.asText.get().trim()
}.getOrDefault("")

val appVersionCode = gitValue("rev-list", "--count", "HEAD").toIntOrNull() ?: 1
val appVersionName = (
    project.findProperty("VERSION_NAME") as String?
    ?: gitValue("log", "-1", "--format=%cd", "--date=format:%y.%m").ifBlank { "0.1" }
).removePrefix("v")

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.mikepenz.aboutlibraries.plugin")
}

android {
    namespace = "me.acardia.amalor"
    compileSdk = 37

    defaultConfig {
        applicationId = "me.acardia.amalor"
        minSdk = 31
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    val signingProperties = Properties().apply {
        rootProject.file("keystore.properties").takeIf { it.isFile }?.inputStream()?.use(::load)
    }
    val hasReleaseSigning = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
        .all { signingProperties.getProperty(it)?.isNotBlank() == true }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(signingProperties.getProperty("storeFile"))
                storePassword = signingProperties.getProperty("storePassword")
                keyAlias = signingProperties.getProperty("keyAlias")
                keyPassword = signingProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set(
                "Amalor_v${appVersionName}_${appVersionCode}-${variant.name}.apk",
            )
        }
    }
}

aboutLibraries {
    library {
        duplicationMode = com.mikepenz.aboutlibraries.plugin.DuplicateMode.MERGE
        duplicationRule = com.mikepenz.aboutlibraries.plugin.DuplicateRule.SIMPLE
    }
}

kotlin { jvmToolchain(25) }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("androidx.core:core-splashscreen:1.2.0")
    implementation("top.yukonga.miuix.kmp:miuix-nav-android:0.9.4-103b737b-SNAPSHOT")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3:1.5.0-alpha27")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.materialkolor:material-kolor:5.0.1")
    implementation("com.mikepenz:aboutlibraries-core:15.2.0")
    implementation("com.mikepenz:aboutlibraries-compose-m3:15.2.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
