import com.android.build.api.variant.HostTestBuilder
import org.gradle.process.CommandLineArgumentProvider
import org.gradle.process.ExecOperations
import javax.inject.Inject

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

/** 执行期只使用显式目录和 Gradle 服务，不捕获脚本/Project 对象。 */
abstract class VerifyLiveEngine : DefaultTask() {
    @get:Internal
    abstract val repository: DirectoryProperty
    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun verify() {
        val root = repository.get().asFile
        val result = execOperations.exec {
            workingDir(root)
            commandLine("python3", "scripts/native/build_native.py", "verify-artifacts")
            isIgnoreExitValue = true
        }
        if (result.exitValue != 0) throw GradleException("LIVE_ENGINE_MISSING: 正式双 ABI 产物或输入身份无效；先运行正式 native 构建。")
    }
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
        versionName = "0.1.0-dev"

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
        getByName("live").jniLibs.directories += rootProject.file("build/native/jni").absolutePath
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
    finalizeDsl { extension ->
        // live 只交付已构建核心的双 ABI，避免只有 Compose 平台库却无法加载核心的 APK。
        extension.productFlavors.getByName("live").ndk.abiFilters += setOf("arm64-v8a", "x86_64")
    }
    beforeVariants(selector().withBuildType("release")) { variant ->
        if (variant.productFlavors.any { it.second == "demo" }) {
            variant.enable = false
        } else {
            // AGP 默认只启用 debug 单测；显式验证 liveRelease 不依赖调试输入类型。
            variant.hostTests.getValue(HostTestBuilder.UNIT_TEST_TYPE).enable = true
        }
    }
}

val verifyLiveEngine = tasks.register<VerifyLiveEngine>("verifyLiveEngine") {
    group = "verification"
    description = "核对正式双 ABI 原生产物与构建输入身份"
    repository.set(rootProject.layout.projectDirectory)
}

tasks.configureEach {
    if (name.startsWith("preLive") && name.endsWith("Build")) {
        dependsOn(verifyLiveEngine)
    }
}

dependencies {
    implementation(project(":contracts"))
    "liveImplementation"(project(":vpn"))
    "androidTestLiveImplementation"(project(":core-bridge"))
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
