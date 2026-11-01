plugins {
    alias(libs.plugins.android.application)
    // org.jetbrains.kotlin.android is no longer applied: AGP 9's built-in Kotlin support
    // (https://developer.android.com/build/migrate-to-built-in-kotlin) replaces it and the two
    // cannot coexist (applying both crashes with "Cannot add extension with name 'kotlin'").
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.pitest)
}

android {
    namespace = "com.ai.notes"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.ai.notes"
        minSdk = 33
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // No kotlin.compilerOptions { jvmTarget = ... } block needed: under AGP 9's built-in Kotlin
    // support, kotlin.compilerOptions.jvmTarget defaults to android.compileOptions.targetCompatibility
    // (JVM 17, set above), per https://developer.android.com/build/migrate-to-built-in-kotlin.

    buildFeatures {
        compose = true
        buildConfig = true
    }

    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
        arg("appfunctions:aggregateAppFunctions", "true")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/LICENSE.md"
            excludes += "META-INF/LICENSE-notice.md"
        }
    }
}

// AGP 9's built-in Kotlin + KSP does not register KSP's generated `resources/assets`
// directory as Android assets, so the AppFunctions registration XML (app_functions_v2.xml)
// never reaches the APK. Wire it in per-variant, with an explicit task dependency.
// Reference: https://github.com/philipplackner/AppFunctionsDemo/blob/master/app/build.gradle.kts
androidComponents {
    onVariants { variant ->
        val kspAssets = layout.buildDirectory
            .dir("generated/ksp/${variant.name}/resources/assets")
        kspAssets.get().asFile.mkdirs()
        variant.sources.assets?.addStaticSourceDirectory(kspAssets.get().asFile.absolutePath)
    }
}

// Ensure KSP (which produces app_functions_v2.xml) runs before assets are merged.
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    val capital = name.removePrefix("merge").removeSuffix("Assets")
    dependsOn(tasks.matching { it.name == "ksp${capital}Kotlin" })
}

pitest {
    targetClasses.set(
        providers.gradleProperty("pitTargets").map { it.split(",") }.orElse(listOf("com.ai.notes.*"))
    )
    // Only generated, framework-wiring, or Compose UI code goes here; never logic classes.
    excludedClasses.set(listOf(
        "com.ai.notes.BuildConfig", "*.R", "*.R\$*",
        "*_Impl", "*_Impl\$*",                       // Room-generated DAO/DB implementations
        "*\$\$serializer",                           // kotlinx-serialization generated serializers
        "com.ai.notes.AppFunctions.\$*",             // AppFunctions KSP-generated invoker/inventory
        "com.ai.notes.data.model.\$*",               // AppFunctions KSP-generated factories
        "*\$\$inlined\$*",                           // kotlinx.coroutines Flow operators inlined into our classes
        "*ComposableSingletons*", "*.ui.screens.*", "*.ui.components.*", "*.ui.theme.*",
        "*.ui.navigation.*", "*Activity*",           // Compose UI: covered by instrumented tests
        "com.ai.notes.App", "com.ai.notes.data.database.NoteDatabase*",
        "com.ai.notes.data.ai.RetrofitFactory", "com.ai.notes.*AppLogger*", // Android/DI wiring, no logic
    ))
    excludedMethods.set(listOf("toString", "hashCode", "component*", "copy", "copy\$default"))
    // DEFAULTS rather than STRONGER: STRONGER adds REMOVE_CONDITIONALS_EQUAL_IF, which rewrites the
    // `== COROUTINE_SUSPENDED` check in every suspend state machine into hundreds of unkillable
    // mutants. DEFAULTS' REMOVE_CONDITIONALS_EQUAL_ELSE still hits those checks (expect ~80
    // survivors on suspend-call lines; ignore them). The ORDER variants add </<=/>/>= coverage,
    // which the coroutine machinery never emits.
    mutators.set(listOf("DEFAULTS", "REMOVE_CONDITIONALS_ORDER_IF", "REMOVE_CONDITIONALS_ORDER_ELSE"))
    threads.set(Runtime.getRuntime().availableProcessors())
    outputFormats.set(listOf("HTML", "XML"))
    timestampedReports.set(false)
    timeoutConstInMillis.set(4000)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.appfunctions)
    implementation(libs.androidx.appfunctions.service)
    ksp(libs.androidx.appfunctions.compiler)

    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization.converter)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.security.crypto)

    debugImplementation(libs.timber)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.mockk.android)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
