plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "br.com.instaladortv"
    compileSdk = 35

    defaultConfig {
        applicationId = "br.com.instaladortv"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"

        // Painel do admin embutido no build: -Pinstaladortv.panelUrl=https://... -Pinstaladortv.appKey=...
        // Vazio = o app pede o endereço e a chave em Configurações.
        val panelUrl = (project.findProperty("instaladortv.panelUrl") as String?).orEmpty()
        val appKey = (project.findProperty("instaladortv.appKey") as String?).orEmpty()
        buildConfigField("String", "PANEL_URL", "\"$panelUrl\"")
        buildConfigField("String", "PANEL_APP_KEY", "\"$appKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/DEPENDENCIES")
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.bouncycastle.pkix)
    implementation(libs.bouncycastle.prov)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.browser)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
