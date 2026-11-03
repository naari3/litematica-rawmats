pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        maven("https://maven.kikugie.dev/releases") { name = "KikuGie Releases" }
        maven("https://maven.kikugie.dev/snapshots") { name = "KikuGie Snapshots" }
    }
}

plugins {
    id("dev.kikugie.stonecutter") version "0.9.6"
    // 26.1+ (unobfuscated) と 1.21.x (obfuscated) で loom の扱いが変わるのを吸収する
    // (https://codeberg.org/KikuGie/loom-back-compat)
    id("dev.kikugie.loom-back-compat") version "0.3"
    // Java 21 (1.21.11 用) をローカルに無くても自動取得できるようにする
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

stonecutter {
    create(rootProject) {
        // 対応バージョンは litematica の現行リリース系列 (1.21.11 / 26.1.x / 26.2 / 26.3) に合わせる。
        // 1.21.10 以前は litematica の materials/json が無い、または malilib GUI API が旧世代のため対象外。
        version("1.21.11", "1.21.11")
        version("26.1.x", "26.1.2")
        version("26.2.x", "26.2")
        version("26.3.x", "26.3")
        vcsVersion = "26.3.x"
    }
}

rootProject.name = "litematica-rawmats"
