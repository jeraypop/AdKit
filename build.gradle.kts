plugins {
    id("com.android.library") version "8.11.0" apply false
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
}

// JitPack 会优先执行 install：确保 AdKit 与三个广告 AAR 都发布到 ~/.m2
tasks.register("install") {
    dependsOn(":adkit:publishToMavenLocal")
}
