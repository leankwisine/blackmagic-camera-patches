group = "app.blackmagic"

patches {
    about {
        name = "Blackmagic Camera Patches"
        description = "Patches for Blackmagic Camera: smoother gallery, usable bitrate tiers, developer options."
        source = "git@github.com:azain47/blackmagic-camera-patches.git"
        author = "azain47"
        contact = "na"
        website = "https://github.com/azain47/blackmagic-camera-patches"
        license = "GPLv3"
    }
}

// Separate configuration so gson is available at runtime for the
// generatePatchesList task but never bundled into the APK.
val patchListGeneratorClasspath = configurations.create("patchListGeneratorClasspath")

dependencies {
    compileOnly(libs.gson)
    patchListGeneratorClasspath(libs.gson)
}

tasks {
    register<JavaExec>("generatePatchesList") {
        description = "Build patch with patch list"

        dependsOn(build)

        classpath = sourceSets["main"].runtimeClasspath + patchListGeneratorClasspath
        mainClass.set("util.PatchListGeneratorKt")
    }

    // Used by gradle-semantic-release-plugin.
    publish {
        dependsOn("generatePatchesList")
    }
}

val androidSdk = providers.environmentVariable("ANDROID_HOME")
    .orElse(providers.environmentVariable("ANDROID_SDK_ROOT"))
    .orElse(providers.provider {
        throw GradleException("Set ANDROID_HOME to an Android SDK with platform 33 and build-tools 36.1.0.")
    })
val androidJar = androidSdk.map { file("$it/platforms/android-33/android.jar") }
val d8Jar = androidSdk.map { file("$it/build-tools/36.1.0/lib/d8.jar") }
val monitorExtension = sourceSets.create("monitorExtension")

dependencies {
    add(monitorExtension.compileOnlyConfigurationName, files(androidJar))
    testImplementation(monitorExtension.output)
    testImplementation("junit:junit:4.13.2")
}

tasks.named<JavaCompile>(monitorExtension.compileJavaTaskName) {
    options.release.set(11)
    options.encoding = "UTF-8"
}

val monitorDexDirectory = layout.buildDirectory.dir("monitor-extension")
val buildMonitorExtension = tasks.register<JavaExec>("buildMonitorExtension") {
    dependsOn(monitorExtension.classesTaskName)
    classpath = files(d8Jar)
    mainClass.set("com.android.tools.r8.D8")
    inputs.files(monitorExtension.output.classesDirs, androidJar)
    outputs.file(monitorDexDirectory.map { it.file("classes.dex") })

    doFirst { monitorDexDirectory.get().asFile.mkdirs() }
    argumentProviders.add(CommandLineArgumentProvider {
        listOf("--min-api", "33", "--lib", androidJar.get().absolutePath,
            "--output", monitorDexDirectory.get().asFile.absolutePath) +
            monitorExtension.output.classesDirs.asFileTree.matching { include("**/*.class") }
                .files.map { it.absolutePath }.sorted()
    })
}

tasks.processResources {
    dependsOn(buildMonitorExtension)
    from(monitorDexDirectory.map { it.file("classes.dex") }) {
        rename { "remote-monitor.mpe" }
    }
}
