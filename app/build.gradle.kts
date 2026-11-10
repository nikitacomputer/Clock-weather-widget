plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.clockweather.widget"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.clockweather.widget"
        // Android 12+: size-responsive widget layouts, RemoteCollectionItems and
        // runtime layout-size setters are all used by the widget renderer.
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}

android.testOptions.unitTests.isIncludeAndroidResources = true

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}

// Robolectric downloads its Android runtime jar itself; allow pointing it at a mirror
// with -ProbolectricRepo=<url>.
tasks.withType<Test>().configureEach {
    providers.gradleProperty("robolectricRepo").orNull?.let { systemProperty("robolectric.dependency.repo.url", it) }
}
