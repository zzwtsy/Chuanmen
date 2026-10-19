plugins { alias(libs.plugins.android.library) }

android {
    namespace = "com.github.zzwtsy.chuanmen.vpn"
    compileSdk { version = release(37) }
    buildToolsVersion = "36.0.0"
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
dependencies {
    api(project(":contracts"))
    implementation(project(":core-bridge"))
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
