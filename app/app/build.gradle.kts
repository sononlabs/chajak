plugins {
    id("com.android.application")
}

android {
    namespace = "ai.sonon.chajak"
    compileSdk = 36
    // 주행 규제(CarUxRestrictions) 확인용. AAOS 기기에는 기본으로 들어 있다.
    useLibrary("android.car")
    defaultConfig {
        applicationId = "ai.sonon.chajak"
        minSdk = 28
        targetSdk = 36
        versionCode = 7
        versionName = "0.4.3-drive"
        // 차작 서버 주소. 에뮬레이터 기본 = PC(10.0.2.2). 심사용 APK 는 ./gradlew assembleDebug -PchajakServer=https://… 로 바꿔 빌드
        buildConfigField("String", "SERVER_URL", "\"${(project.findProperty("chajakServer") ?: "http://10.0.2.2:8790")}\"")
    }
    buildFeatures {
        buildConfig = true
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
    // Pleos Connect Music/Audio 카테고리(2026-11-05 가이드):
    // Media3 표준 미디어 라이브러리 + 세션 + 검색 + 스티어링휠. 라이브러리는 ChajakMediaService 가 들고 있다.
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
    implementation("androidx.media3:media3-common:1.11.1")
    // Media3 세션 API(ListenableFuture, ImmutableList)가 쓰는 Guava
    implementation("com.google.guava:guava:33.4.8-android")
}
