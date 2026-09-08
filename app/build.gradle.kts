plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseVariables = listOf("YEOUN_KEYSTORE_PATH", "YEOUN_KEYSTORE_PASSWORD", "YEOUN_KEY_ALIAS", "YEOUN_KEY_PASSWORD")
val releaseValues = releaseVariables.associateWith { providers.environmentVariable(it).orNull }
val hasReleaseSigning = releaseValues.values.all { !it.isNullOrBlank() }
if (providers.gradleProperty("requireReleaseSigning").orNull == "true") {
    check(hasReleaseSigning) { "Release signing requires all YEOUN_KEYSTORE_* and YEOUN_KEY_* environment variables." }
}

android {
    namespace = "dev.kuass.ivlyrics"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.kuass.ivlyrics"
        minSdk = 26
        targetSdk = 36
        testInstrumentationRunner = "dev.kuass.ivlyrics.QualityInstrumentation"
        versionCode = 3
        versionName = "0.4.0"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseValues.getValue("YEOUN_KEYSTORE_PATH")!!)
                storePassword = releaseValues.getValue("YEOUN_KEYSTORE_PASSWORD")
                keyAlias = releaseValues.getValue("YEOUN_KEY_ALIAS")
                keyPassword = releaseValues.getValue("YEOUN_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("release") {
            isDebuggable = false
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("com.google.android.material:material:1.13.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260522")
}
