import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import org.gradle.api.tasks.PathSensitivity

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val propBuildTime = project.findProperty("buildTime") as? String 
    ?: SimpleDateFormat("yyyy.MM.dd", Locale.CHINA).format(Date())
val propBuildTag = project.findProperty("buildTag") as? String 
    ?: "Release 7 (Surge Architecture & Outbound Control)"
val propVersionCode = (project.findProperty("versionCode") as? String)?.toIntOrNull() ?: 70
val propVersionName = project.findProperty("versionName") as? String ?: "0.3.0"

android {
    namespace = "com.mirage.android"
    compileSdk = 36
    // 唯一来源见 gradle/libs.versions.toml 的 ndk
    ndkVersion = libs.versions.ndk.get()

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
    val keystoreProps = Properties().apply {
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
                // rootProject.file 解析 keystore.properties (项目根), 但 android {} 块内的
                // file() 是相对模块目录 (app/) 解析的。两者基准不一致会让
                // storeFile=ks.jks 被找成 app/ks.jks —— 统一按项目根解析。
                storeFile = rootProject.file(ksPath!!)
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.fragment.ktx)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}

// LocalizationTest 直接读 src/main/res 下的 strings.xml 与 layout, 而这些文件不在
// 单测任务的默认输入里 —— 不声明的话改了资源 Gradle 仍判 UP-TO-DATE 跳过测试,
// 门禁会静默失效 (实测: 删掉 values-en 的一个 key, 测试根本没跑就 BUILD SUCCESSFUL)。
tasks.withType<Test>().configureEach {
    inputs.dir("src/main/res")
        .withPropertyName("resForLocalizationTest")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
