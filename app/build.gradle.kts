import java.util.Properties

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.jiwoo.wastesorting"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.jiwoo.wastesorting"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // 서버 주소 (local.properties의 SERVER_URL). Gemini 키는 이제 앱에 없다
        buildConfigField(
            "String", "SERVER_URL",
            "\"${localProps.getProperty("SERVER_URL", "")}\""
        )
    }

    // 정식 서명 키. 경로와 비밀번호는 local.properties에만 둔다 (Git에 올리지 않음)
    // 값이 없으면 release는 서명 없이 만들어진다 (CI 등)
    val releaseStoreFile = localProps.getProperty("RELEASE_STORE_FILE")
    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = localProps.getProperty("RELEASE_STORE_PASSWORD")
                keyAlias = localProps.getProperty("RELEASE_KEY_ALIAS")
                keyPassword = localProps.getProperty("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8: 안 쓰는 코드 제거 + 이름 줄이기. 지켜야 할 규칙은 src/main/keepRules/rules.keep
            optimization {
                enable = true
            }
            if (releaseStoreFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // 오탐 사진을 인터넷이 될 때 나중에라도 보내기 (앱을 꺼도 이어서)
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    val cameraxVersion = "1.4.2"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")
    implementation("com.google.ai.edge.litert:litert:1.0.1")
    implementation("com.google.ai.edge.litert:litert-gpu:1.0.1")
    implementation("com.google.ai.edge.litert:litert-gpu-api:1.0.1")
}