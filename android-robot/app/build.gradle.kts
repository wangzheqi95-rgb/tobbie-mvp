import java.net.URL

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 視訊來電推播（FCM）需要 Firebase 設定檔 app/google-services.json（從 Firebase 主控台下載，不進版控）。
// 沒有這個檔也能建置，只是收不到視訊來電推播（機器人仍會在每分鐘的心跳看到來電）。
if (file("google-services.json").exists()) apply(plugin = "com.google.gms.google-services")

android {
    namespace = "tw.neara.robot"
    compileSdk = 34

    defaultConfig {
        applicationId = "tw.neara.robot"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        // 雲端後端網址（Cloud Functions）。空字串＝只在本機運作，不上傳。
        buildConfigField("String", "DEFAULT_BACKEND_URL", "\"\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    androidResources { noCompress += "task" }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("com.google.mediapipe:tasks-vision:0.10.14")
    implementation("androidx.health.connect:connect-client:1.1.0-alpha07") // 智慧手錶（Health Connect）
    implementation(platform("com.google.firebase:firebase-bom:33.5.1"))
    implementation("com.google.firebase:firebase-messaging")             // 視訊來電響鈴
    implementation("io.agora.rtc:full-sdk:4.3.2")                         // 視訊通話（Agora）
}

// 第一次建置時自動下載姿勢辨識模型（約 5.5 MB）到 assets
val downloadPoseModel by tasks.registering {
    val out = file("src/main/assets/pose_landmarker_lite.task")
    outputs.file(out)
    onlyIf { !out.exists() }
    doLast {
        out.parentFile.mkdirs()
        URL("https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task")
            .openStream().use { input -> out.outputStream().use { input.copyTo(it) } }
    }
}
tasks.named("preBuild") { dependsOn(downloadPoseModel) }
