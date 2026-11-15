import java.util.Properties

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
        versionCode = 16
        versionName = "0.5.0"
        // 차작 서버 주소. 에뮬레이터 기본 = PC(10.0.2.2). 심사용 APK 는 ./gradlew assembleDebug -PchajakServer=https://… 로 바꿔 빌드
        buildConfigField("String", "SERVER_URL", "\"${(project.findProperty("chajakServer") ?: "http://10.0.2.2:8790")}\"")
        // 서버 공유 토큰(저장소에 넣지 않는다): -PchajakToken=… 또는 이 PC 의 ~/.chajak/token(서버와 같은 파일)
        val tokenFile = File(System.getProperty("user.home"), ".chajak/token")
        val token = (project.findProperty("chajakToken") as String?) ?: (if (tokenFile.exists()) tokenFile.readText().trim() else "")
        buildConfigField("String", "SERVER_TOKEN", "\"$token\"")
    }
    // 제출용 release 서명(2026-11-11, 44번 #5): 키와 비밀번호는 저장소 밖 ~/.chajak/keystore.properties 에만 둔다.
    // 그 파일이 없는 PC 에서는 release 가 서명 없이 만들어진다(debug 빌드는 그대로).
    val keyProps = Properties()
    File(System.getProperty("user.home"), ".chajak/keystore.properties").takeIf { it.exists() }?.inputStream()?.use { keyProps.load(it) }
    signingConfigs {
        if (keyProps["storeFile"] as String? != null) create("upload") {
            storeFile = file(keyProps["storeFile"] as String)
            storePassword = keyProps["storePassword"] as String?
            keyAlias = keyProps["keyAlias"] as String?
            keyPassword = keyProps["keyPassword"] as String?
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("upload")?.let { signingConfig = it }
        }
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
