plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "kr.re.kimt.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "kr.re.kimt.mobile"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    // 고정 서명키: 새 버전을 받아도 기존 앱 위에 그대로 업데이트 설치되게
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keystore/kimt.jks")
            storePassword = "kimtmobile"
            keyAlias = "kimt"
            keyPassword = "kimtmobile"
        }
    }
    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
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
    // QR 찍기 — 구글 플레이 서비스의 스캐너 화면을 빌려 쓴다 (카메라 권한 필요 없음)
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
}
