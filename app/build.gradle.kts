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

/** 只在消费 JNI 库时构建 Rust；输入输出由 Gradle 追踪，不拦截 JVM 测试或 lint。 */
abstract class BuildNative : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection
    @get:Input
    abstract val sdkPath: Property<String>
    @get:OutputDirectory
    abstract val jniDirectory: DirectoryProperty
    @get:OutputFile
    abstract val reportFile: RegularFileProperty
    @get:Internal
    abstract val repository: DirectoryProperty
    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun build() {
        val root = repository.get().asFile
        val result = execOperations.exec {
            workingDir(root)
            commandLine("python3", "scripts/native/build_native.py", "build", "--sdk", sdkPath.get())
        }
        result.assertNormalExitValue()
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
        ndk.abiFilters += setOf("arm64-v8a", "x86_64")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = true
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

val buildNative = tasks.register<BuildNative>("buildNative") {
    group = "build"
    description = "使用固定工具链构建双 ABI Rust 核心"
    repository.set(rootProject.layout.projectDirectory)
    sources.from(fileTree("native") {
        include("src/**", "host/**", "patches/**", "Cargo.toml", "Cargo.lock", "inputs.json")
    }, rootProject.file("scripts/native/build_native.py"))
    sdkPath.set(androidComponents.sdkComponents.sdkDirectory.map { it.asFile.absolutePath })
    jniDirectory.set(layout.buildDirectory.dir("native/jni"))
    reportFile.set(layout.buildDirectory.file("native/build-report.json"))
}

androidComponents.onVariants { variant ->
    variant.sources.jniLibs?.addGeneratedSourceDirectory(buildNative, BuildNative::jniDirectory)
}

dependencies {
    implementation(libs.gson)
    implementation(libs.kotlinx.coroutines.core)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
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
}
