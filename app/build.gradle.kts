plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
}

android {
    namespace = "com.sunmapper"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sunmapper"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        vectorDrawables.useSupportLibrary = true
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders += mapOf(
            "ARCORE_API_KEY" to (project.findProperty("ARCORE_API_KEY") ?: "")
        )

    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
        // Enable desugaring for java.time
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        viewBinding = true
    }

    packaging {
        resources {
            // Exclude duplicates from Ktor/Netty dependencies
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
        }
    }
}

dependencies {


    implementation("com.google.ar:core:1.34.0")
    implementation("com.google.code.gson:gson:2.8.9")
    implementation("com.google.android.material:material:1.9.0")

    // Ktor server for WebSocket streaming
    implementation("io.ktor:ktor-server-core:1.6.4")
    implementation("io.ktor:ktor-server-netty:1.6.4")
    implementation("io.ktor:ktor-gson:1.6.4")
    implementation("io.ktor:ktor-websockets:1.6.4")
    implementation(libs.play.services.location)

    // Java 8+ desugaring for java.time
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:1.1.6")

    // AndroidX and Sceneform
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.ar.core)
    implementation(libs.sceneform.ux)
    implementation(libs.assets)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
