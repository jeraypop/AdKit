import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.tasks.GenerateModuleMetadata

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

// JitPack / mavenLocal 统一坐标：com.github.jeraypop:AdKit
group = "com.github.jeraypop"
version = "1.0.6"

android {
    namespace = "com.jeraypop.adkit"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }

    lint {
        abortOnError = false
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // 编译期只需要穿山甲 API；GDT 与 adapter 由 POM 在运行时带入
    compileOnly(files("libs/open_ad_sdk.aar"))
    compileOnly("androidx.appcompat:appcompat:1.7.0")
    compileOnly("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.annotation:annotation:1.9.1")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            afterEvaluate {
                from(components["release"])
            }
            artifactId = "AdKit"
            pom {
                packaging = "aar"
                withXml {
                    val root = asNode()
                    val existing = root.get("dependencies") as groovy.util.NodeList
                    val dependencies = if (existing.isNotEmpty()) {
                        existing[0] as groovy.util.Node
                    } else {
                        root.appendNode("dependencies")
                    }

                    fun addDependency(groupId: String, artifactId: String, version: String, scope: String) {
                        val dependency = dependencies.appendNode("dependency")
                        dependency.appendNode("groupId", groupId)
                        dependency.appendNode("artifactId", artifactId)
                        dependency.appendNode("version", version)
                        dependency.appendNode("scope", scope)
                    }

                    // 同一 JitPack 构建里发布的三个广告 SDK artifact
                    addDependency("com.github.jeraypop", "open_ad_sdk", project.version.toString(), "runtime")
                    addDependency("com.github.jeraypop", "GDTSDK", project.version.toString(), "runtime")
                    addDependency("com.github.jeraypop", "mediation_gdt_adapter", project.version.toString(), "runtime")

                    // 广告 SDK 运行所需的 AndroidX 依赖
                    addDependency("androidx.appcompat", "appcompat", "1.7.0", "compile")
                    addDependency("androidx.recyclerview", "recyclerview", "1.3.2", "compile")
                }
            }
        }

        register<MavenPublication>("pangleSdk") {
            artifactId = "open_ad_sdk"
            artifact("libs/open_ad_sdk.aar") {
                extension = "aar"
            }
            pom {
                packaging = "aar"
            }
        }

        register<MavenPublication>("gdtSdk") {
            artifactId = "GDTSDK"
            artifact("libs/GDTSDK.unionNormal.4.701.1571.aar") {
                extension = "aar"
            }
            pom {
                packaging = "aar"
            }
        }

        register<MavenPublication>("gdtAdapter") {
            artifactId = "mediation_gdt_adapter"
            artifact("libs/mediation_gdt_adapter_4.701.1571.0.aar") {
                extension = "aar"
            }
            pom {
                packaging = "aar"
            }
        }
    }
}

// 让消费者统一走 POM，避免 Gradle Module Metadata 丢失三个广告 AAR 依赖
tasks.withType<GenerateModuleMetadata>().configureEach {
    enabled = false
}
