import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.4.20"
    id("net.fabricmc.fabric-loom") version "1.17-SNAPSHOT"
    id("maven-publish")
}

version = project.property("mod_version") as String
group = project.property("maven_group") as String

base {
    archivesName.set(project.property("archives_base_name") as String)
}

val targetJavaVersion = 25
java {
    toolchain.languageVersion = JavaLanguageVersion.of(targetJavaVersion)
    // Loom will automatically attach sourcesJar to a RemapSourcesJar task and to the "build" task
    // if it is present.
    // If you remove this line, sources will not be generated.
    withSourcesJar()
}

loom {
    splitEnvironmentSourceSets()

    mods {
        register("differangle") {
            sourceSet("main")
            sourceSet("client")
        }
    }
}

fabricApi {
    configureDataGeneration {
        client = true
    }
}

repositories {
    mavenCentral()
    exclusiveContent {
        forRepository { maven { name = "Modrinth"; url = uri("https://api.modrinth.com/maven") } }
        filter { includeGroup("maven.modrinth") }
    }
    // Add repositories to retrieve artifacts from in here.
    // You should only use this when depending on other mods because
    // Loom adds the essential maven repositories to download Minecraft and libraries from automatically.
    // See https://docs.gradle.org/current/userguide/declaring_repositories.html
    // for more information about repositories.
}

dependencies {
    // Optional renderer API; the mod is never bundled or required at runtime.
    val sodiumApi = fileTree("run/mods") { include("*sodium-fabric-0.9.1+mc26.2.jar") }
    val irisApi = fileTree("run/mods") { include("iris-fabric-1.11.2+mc26.2.jar") }
    "clientCompileOnly"(if (sodiumApi.isEmpty) "maven.modrinth:AANobbMI:2Yom1N68" else sodiumApi)
    "clientCompileOnly"(if (irisApi.isEmpty) "maven.modrinth:YL57xq9U:oaD6KQls" else irisApi)
    // To change the versions see the gradle.properties file
    minecraft("com.mojang:minecraft:${project.property("minecraft_version")}")
    implementation("net.fabricmc:fabric-loader:${project.property("loader_version")}")
    implementation("net.fabricmc:fabric-language-kotlin:${project.property("kotlin_loader_version")}")

    implementation("net.fabricmc.fabric-api:fabric-api:${project.property("fabric_version")}")

    // Cross-platform media playback. These remain external Fabric dependencies so Differangle does
    // not redistribute WaterMedia or its native bundle inside its own jar.
    implementation("maven.modrinth:G922NeHS:FdCZ5Rxq") // WaterMedia 3.0.0.23
    implementation("maven.modrinth:4997XcoK:fYWsOuBz") // WaterMedia Binaries 3.0.0.6
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.1")
}

tasks.test {
    useJUnitPlatform()
}

// Isolated GPU integration test mod; never packaged in the production jar.
if (providers.gradleProperty("cameraGameTest").isPresent) {
    val cameraTest = sourceSets.create("cameraGameTest") {
        compileClasspath += sourceSets.main.get().output + sourceSets["client"].output + sourceSets["client"].compileClasspath
        runtimeClasspath += sourceSets.main.get().output + sourceSets["client"].output + sourceSets["client"].runtimeClasspath
    }
    loom.mods.register("differangle_test") { sourceSet(cameraTest) }
    val cameraShaderTest = providers.gradleProperty("cameraShaderTest").isPresent
    val cameraMirrorTest = providers.gradleProperty("cameraMirrorTest").isPresent
    tasks.named<ProcessResources>(cameraTest.processResourcesTaskName) {
        inputs.property("cameraShaderTest", cameraShaderTest)
        inputs.property("cameraMirrorTest", cameraMirrorTest)
        if (cameraShaderTest || cameraMirrorTest) filesMatching("fabric.mod.json") {
            filter { line -> if (line.contains("\"fabric-client-gametest\":"))
                "    \"fabric-client-gametest\": [\"net.astrorbits.differangle.test.${if (cameraMirrorTest) "CameraMirrorGameTest" else "CameraShaderGameTest"}\"]" else line }
        }
    }
    loom.runs.register("cameraTest") {
        client()
        name = "Differangle Camera Integration Test"
        source(cameraTest)
        runDir("build/camera-gametest")
        vmArg("-Dfabric.client.gametest")
        vmArg("-Dfabric.client.gametest.modid=differangle_test")
    }
}

tasks.processResources {
    inputs.property("version", project.version)
    inputs.property("minecraft_version", project.property("minecraft_version"))
    inputs.property("loader_version", project.property("loader_version"))
    inputs.property("kotlin_loader_version", project.property("kotlin_loader_version"))
    filteringCharset = "UTF-8"

    filesMatching("fabric.mod.json") {
        expand(
            "version" to project.version,
            "minecraft_version" to project.property("minecraft_version").toString(),
            "loader_version" to project.property("loader_version").toString(),
            "kotlin_loader_version" to project.property("kotlin_loader_version").toString()
        )
    }
}

tasks.withType<JavaCompile>().configureEach {
    // ensure that the encoding is set to UTF-8, no matter what the system default is
    // this fixes some edge cases with special characters not displaying correctly
    // see http://yodaconditions.net/blog/fix-for-java-file-encoding-problems-with-gradle.html
    // If Javadoc is generated, this must be specified in that task too.
    options.encoding = "UTF-8"
    options.release.set(targetJavaVersion)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.fromTarget(targetJavaVersion.toString()))
}

tasks.jar {
    from("LICENSE.txt") {
        rename { "${it}_${project.base.archivesName.get()}" }
    }
}

// configure the maven publication
publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            artifactId = project.property("archives_base_name") as String
            from(components["java"])
        }
    }

    // See https://docs.gradle.org/current/userguide/publishing_maven.html for information on how to set up publishing.
    repositories {
        // Add repositories to publish to here.
        // Notice: This block does NOT have the same function as the block in the top level.
        // The repositories here will be used for publishing your artifact, not for
        // retrieving dependencies.
    }
}
