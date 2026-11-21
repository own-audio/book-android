plugins {
    id("ownaudio.android.library")
    alias(libs.plugins.ksp)
}

android {
    namespace = "audio.own.book.library"
    testOptions {
        // Robolectric runs the Room tests on the JVM and needs the merged manifest.
        unitTests.isIncludeAndroidResources = true
    }
}

ksp {
    // Exported so a schema change shows up in review and migrations can be tested against it.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // api: the sources take the kits' clients and hand out the timeline's parts.
    api(project(":kit:ownaudio"))
    api(project(":kit:audiobookshelf"))
    api(project(":core:servers"))
    api(project(":player:spoken"))
    api(libs.kotlinx.coroutines.core)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}

// The live tests run only when the environment names a server. The hosts are inputs so a result
// from a run without them is not reused; the passwords deliberately are not, so they never land
// in Gradle's task history.
tasks.withType<Test>().configureEach {
    inputs.property("ownAudioHost", providers.environmentVariable("OWNAUDIO_HOST").orElse(""))
    inputs.property("audiobookshelfHost", providers.environmentVariable("AUDIOBOOKSHELF_HOST").orElse(""))
}
