plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Apple's ShazamKit AAR is not on Maven. Place it at app/libs/ (git-ignored); without it the app still builds
// and honestly reports "SDK not linked".
val shazamKitAar = file("libs/shazamkit-android-release.aar")
val shazamKitLinked = shazamKitAar.exists()

android {
    namespace = "com.mundymorning.touchmusicid"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mundymorning.touchmusicid"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-dev"
        // Public URL of the operator's token service. Empty by default: no secrets, no token in the APK.
        val tokenUrl = (project.findProperty("TOKEN_SERVICE_URL") as String?).orEmpty()
        buildConfigField("String", "TOKEN_SERVICE_URL", "\"$tokenUrl\"")
        buildConfigField("boolean", "SHAZAMKIT_LINKED", "$shazamKitLinked")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Optional: run Robolectric offline from a pre-fetched android-all jar directory (-ProbolectricDir=...).
        val offlineDir = project.findProperty("robolectricDir") as String?
        if (offlineDir != null) {
            unitTests.all {
                it.systemProperty("robolectric.offline", "true")
                it.systemProperty("robolectric.dependency.dir", offlineDir)
            }
        }
    }
    sourceSets.getByName("main").java.srcDir(if (shazamKitLinked) "src/shazamkit/kotlin" else "src/noshazamkit/kotlin")
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    val room = "2.6.1"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")

    if (shazamKitLinked) {
        implementation(files("libs/shazamkit-android-release.aar"))
        // Runtime dependencies of the AAR, versions per Apple's ShazamKit Android page (AAR carries no POM).
        implementation("com.squareup.okhttp3:okhttp:4.12.0")
        implementation("com.squareup.retrofit2:retrofit:2.11.0")
        implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    }

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("androidx.room:room-testing:$room")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
}
