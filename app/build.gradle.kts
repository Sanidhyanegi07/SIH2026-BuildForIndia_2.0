plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("com.google.gms.google-services")
}

// OneDrive-hosted checkouts lock intermediate files while syncing, which
// breaks AGP's file merging (java.nio.file.AccessDeniedException). Builds
// can be relocated off the synced path by setting GEORESCUX_BUILD_DIR.
System.getenv("GEORESCUX_BUILD_DIR")?.let { buildDirPath ->
    layout.buildDirectory.set(file(buildDirPath))
}

android {
    namespace = "com.example.georescux"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.example.georescux"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    packaging {
        // The datastore JNI library ships 4KB-aligned; compress it so the
        // APK installs on 16KB-page devices (e.g. the 16 KB page-size
        // emulator images and upcoming 16 KB-page phones).
        jniLibs {
            useLegacyPackaging = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.google.play.services.location)
    implementation(libs.osmdroid.android)
    implementation("org.osmdroid:osmdroid-mapsforge:6.1.20")
    implementation("org.mapsforge:mapsforge-map-android:0.20.0")
    implementation("org.mapsforge:mapsforge-map-reader:0.20.0")
    implementation("org.mapsforge:mapsforge-themes:0.20.0")
    implementation(libs.androidx.work.runtime.ktx)
    implementation(platform("com.google.firebase:firebase-bom:34.5.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-database")
    implementation("com.google.android.gms:play-services-nearby:19.3.0")
    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}

// Stage 7B-4: the bundled regional graph asset is megabyte-scale; the JSON
// parse + graph build in UttarakhandRegionGraphTest needs more headroom
// than the default unit-test worker heap.
tasks.withType<Test>().configureEach {
    maxHeapSize = "3g"
}
