plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Signing comes from environment variables (set by CI from GitHub Secrets, or by you locally).
// If they are absent, release is left unsigned and debug uses the default Android debug key.
val ksPath: String? = System.getenv("KEYSTORE_PATH")?.takeIf { it.isNotBlank() && file(it).exists() }
val ksPassword: String? = System.getenv("KEYSTORE_PASSWORD")
val ksAlias: String? = System.getenv("KEY_ALIAS")
val ksKeyPassword: String? = System.getenv("KEY_PASSWORD")
val haveSigning = ksPath != null && !ksPassword.isNullOrEmpty() && !ksAlias.isNullOrEmpty() && !ksKeyPassword.isNullOrEmpty()

android {
    namespace = "com.pageflow"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pageflow.reader"
        minSdk = 26
        targetSdk = 34
        versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 4
        versionName = "0.3.1"
    }

    signingConfigs {
        if (haveSigning) {
            create("pageflow") {
                storeFile = file(ksPath!!)
                storePassword = ksPassword
                keyAlias = ksAlias
                keyPassword = ksKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"          // can be installed next to the release build
            manifestPlaceholders["appLabel"] = "PageFlow (debug)"
            // Same persistent key as release when available, so debug builds can update over each other.
            if (haveSigning) signingConfig = signingConfigs.getByName("pageflow")
        }
        release {
            manifestPlaceholders["appLabel"] = "PageFlow"
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (haveSigning) signingConfig = signingConfigs.getByName("pageflow")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }

    // Don't let non-fatal lint findings fail the first CI builds.
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    testOptions { unitTests.isReturnDefaultValues = true }

}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    // PDF text + character positions for selection/copy/highlight (Android's PdfRenderer cannot extract text)
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // Unit tests run on the plain JVM: a real XML pull parser replaces Android's stubbed one.
    testImplementation("junit:junit:4.13.2")
    testImplementation("xmlpull:xmlpull:1.1.3.1")
    testImplementation("net.sf.kxml:kxml2:2.3.0")
}
