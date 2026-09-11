plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation("com.formdev:flatlaf:3.7.2")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("org.json:json:20250517")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("app.lecturevault.desktop.DesktopMainKt")
}

tasks.jar {
    manifest.attributes["Main-Class"] = application.mainClass.get()
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map(::zipTree))
}

tasks.register<Zip>("portableZip") {
    dependsOn(tasks.jar)
    archiveFileName.set("LectureVault-desktop-portable.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(tasks.jar) { rename { "LectureVault.jar" } }
    from("README-desktop.txt")
    from("LectureVault.command") {
        filePermissions { unix("rwxr-xr-x") }
    }
}
