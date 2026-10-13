import org.gradle.process.CommandLineArgumentProvider

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Gradle 先解析固定的测试 runtime；Robolectric 只读该缓存目录，离线测试不能自行下载。
abstract class RobolectricSdkArguments : CommandLineArgumentProvider {
    @get:Classpath
    abstract val sdkFiles: ConfigurableFileCollection

    override fun asArguments() = listOf(
        "-Drobolectric.offline=true",
        "-Drobolectric.dependency.dir=${sdkFiles.singleFile.parentFile.absolutePath}",
    )
}

val robolectricSdk = configurations.create("robolectricSdk")

android {
    namespace = "com.github.zzwtsy.chuanmen"
    compileSdk {
        version = release(37)
    }
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.github.zzwtsy.chuanmen"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "engine"
    productFlavors {
        create("demo") {
            dimension = "engine"
            applicationIdSuffix = ".demo"
            versionNameSuffix = "-demo"
        }
        create("live") {
            dimension = "engine"
        }
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    sourceSets {
        getByName("testDemo").kotlin.directories += "src/demoUiTest/java"
        getByName("androidTestDemo").kotlin.directories += "src/demoUiTest/java"
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

tasks.withType<Test>().configureEach {
    val sdkArguments = objects.newInstance<RobolectricSdkArguments>()
    sdkArguments.sdkFiles.from(robolectricSdk)
    jvmArgumentProviders.add(sdkArguments)
    maxHeapSize = "2g"
    jvmArgs(
        "--enable-native-access=ALL-UNNAMED",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.util=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
        "--add-opens=java.base/java.net=ALL-UNNAMED",
        "--add-opens=java.base/java.security=ALL-UNNAMED",
        "--add-opens=java.base/java.text=ALL-UNNAMED",
        "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
        "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
        "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
    )
}

// demo 仅供本地调试；保留 live 的原有 release 设置和签名约定。
androidComponents {
    beforeVariants(selector().withBuildType("release")) { variant ->
        if (variant.productFlavors.any { it.second == "demo" }) {
            variant.enable = false
        }
    }
}

val verifyLiveEngine = tasks.register("verifyLiveEngine") {
    group = "verification"
    description = "在正式服务和桥接未接入时阻止生成 live 产物"
    doLast {
        throw GradleException(
            "LIVE_ENGINE_MISSING: P02 的正式 VpnService / CoreEngine 尚未接入；" +
                "P00 实验补丁未获得生产批准。演示请显式构建 :app:assembleDemoDebug。"
        )
    }
}

tasks.configureEach {
    if (name.startsWith("preLive") && name.endsWith("Build")) {
        dependsOn(verifyLiveEngine)
    }
}

dependencies {
    implementation(project(":contracts"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.espresso.core)
    robolectricSdk(libs.robolectric.android)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
