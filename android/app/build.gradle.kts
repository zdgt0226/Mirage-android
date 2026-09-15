import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val propBuildTime = project.findProperty("buildTime") as? String 
    ?: SimpleDateFormat("yyyy.MM.dd", Locale.CHINA).format(Date())
val propBuildTag = project.findProperty("buildTag") as? String 
    ?: "Release 7 (Surge Architecture & Outbound Control)"
val propVersionCode = (project.findProperty("versionCode") as? String)?.toIntOrNull() ?: 70
val propVersionName = project.findProperty("versionName") as? String ?: "0.3.0"

android {
    namespace = "com.mirage.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mirage.android"
        minSdk = 26
        targetSdk = 34
        versionCode = propVersionCode
        versionName = propVersionName
        buildConfigField("String", "BUILD_TIME", "\"$propBuildTime\"")
        buildConfigField("String", "BUILD_TAG", "\"$propBuildTag\"")
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    // 分发签名配置。
    //
    // 密钥材料一律来自环境变量或未纳入版本控制的 keystore.properties, 绝不提交进仓库。
    // 四项缺任意一项则不创建 signingConfig, assembleRelease 会产出未签名包并
    // 在下方给出明确提示 —— 宁可构建失败, 也不要静默回落到 AOSP 调试密钥。
    val keystoreProps = java.util.Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    fun secret(key: String, env: String): String? =
        (keystoreProps.getProperty(key) ?: System.getenv(env))?.takeIf { it.isNotBlank() }

    val ksPath = secret("storeFile", "MIRAGE_KEYSTORE_PATH")
    val ksPass = secret("storePassword", "MIRAGE_KEYSTORE_PASSWORD")
    val ksAlias = secret("keyAlias", "MIRAGE_KEY_ALIAS")
    val ksAliasPass = secret("keyPassword", "MIRAGE_KEY_PASSWORD")
    val hasReleaseSigning = ksPath != null && ksPass != null && ksAlias != null && ksAliasPass != null

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(ksPath!!)
                storePassword = ksPass
                keyAlias = ksAlias
                keyPassword = ksAliasPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                logger.warn(
                    "[mirage] 未找到分发签名配置 (keystore.properties 或 MIRAGE_KEYSTORE_* 环境变量), " +
                        "assembleRelease 将产出未签名 APK。切勿用调试密钥分发。"
                )
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        aidl = true
        buildConfig = true
    }

    // 原生库来自 native/mirage-jni 的构建产物 (由 scripts/build-android.sh 拷入)
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")
    implementation("androidx.fragment:fragment-ktx:1.8.4")
    testImplementation("junit:junit:4.13.2")
}
