import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(25)
    compilerOptions { jvmTarget = JvmTarget.JVM_11 }
}
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}
dependencies {
    api(project(":contracts"))
    implementation(libs.gson)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
