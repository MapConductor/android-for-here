plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.mapconductor.here.sample"
    compileSdk = project.property("compileSdk").toString().toInt()

    defaultConfig {
        applicationId = "com.mapconductor.sample.here"
        minSdk = project.property("minSdk").toString().toInt()
        targetSdk = project.property("compileSdk").toString().toInt()
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        compose = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(project.property("javaVersion").toString())
        targetCompatibility = JavaVersion.toVersion(project.property("javaVersion").toString())
    }

    packaging {
        resources {
            excludes += "META-INF/versions/9/previous-compilation-data.bin"
            excludes += "META-INF/*.kotlin_module"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(
            org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(
                project.property("jvmTarget").toString(),
            ),
        )
    }
}

dependencies {
    implementation(findProject(":mapconductor-for-here") ?: project(":"))
    // HERE SDK: place heresdk-*.aar in the project root's libs/ directory.
    // The SDK cannot be redistributed; download it from https://developer.here.com/
    implementation(
        fileTree(
            mapOf(
                "dir" to rootDir.resolve("libs").toString(),
                "include" to arrayOf("heresdk*.jar", "heresdk*.aar"),
            ),
        ),
    )

    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)
}
