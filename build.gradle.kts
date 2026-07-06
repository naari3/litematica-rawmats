plugins {
    // Minecraft バージョンに応じて適切な loom variant を適用する (26.1+ は no-remap 相当)
    id("dev.kikugie.loom-back-compat")
}

version = property("mod.version") as String
base.archivesName = "${property("mod.file_name")}-${sc.current.version}"

// 26.1+ は Java 25、1.21.x は Java 21
val requiredJava: JavaVersion =
    if (sc.current.parsed >= "26.1") JavaVersion.VERSION_25 else JavaVersion.VERSION_21

repositories {
    fun strictMaven(url: String, alias: String, vararg groups: String) = exclusiveContent {
        forRepository { maven(url) { name = alias } }
        filter { groups.forEach(::includeGroupAndSubgroups) }
    }
    // malilib / litematica (sakura-ryoko fork) はここに publish されている
    strictMaven("https://masa.dy.fi/maven/sakura-ryoko", "MasaModding", "fi.dy.masa")
    // conditional-mixin (malilib/litematica の推移的依存)
    strictMaven("https://maven.fallenbreath.me/releases", "FallenBreath", "me.fallenbreath")
    // com.github は jitpack 以外にも本物の group がある (com.github.oshi 等) ので排他にしない
    maven("https://jitpack.io") {
        name = "Jitpack"
        content { includeGroupAndSubgroups("com.github") }
    }
}

dependencies {
    minecraft("com.mojang:minecraft:${sc.current.version}")
    // obfuscated なバージョン (1.21.x) にだけ Mojang mappings を適用する
    loomx.applyMojangMappings()

    modImplementation("net.fabricmc:fabric-loader:${property("deps.fabric_loader")}")
    implementation("com.google.code.findbugs:jsr305:3.0.2")

    // 依存 mod。Litematica 本体の materials/json サブシステムを呼ぶためコンパイル依存する。
    // mod* は 26.1+ では loom-back-compat が plain 構成へ変換する。
    modImplementation("fi.dy.masa.malilib:malilib-fabric-${sc.current.version}:${property("deps.malilib")}")
    modImplementation("fi.dy.masa.litematica:litematica-fabric-${sc.current.version}:${property("deps.litematica")}")

    // ユニットテスト。fabric-loader-junit が fabric 環境下で JUnit を起動し、
    // Minecraft クラス + registry bootstrap (SharedConstants/Bootstrap) をテストから使えるようにする。
    testImplementation("net.fabricmc:fabric-loader-junit:${property("deps.fabric_loader")}")
}

loom {
    runConfigs.all {
        // バージョン間で run ディレクトリを共有する
        runDirectory = rootProject.file("run")
        // コミット残が狭いマシン向けにクライアントヒープを抑える。
        // 大きな schematic を扱うなら値を上げる。
        jvmArguments.add("-Xmx1536m")
        // 開発時は rawmats logger を DEBUG に上げる (InitHandler が読む)
        jvmArguments.add("-Drawmats.debug=true")
    }
}

java {
    withSourcesJar()
    sourceCompatibility = requiredJava
    targetCompatibility = requiredJava

    toolchain {
        vendor = JvmVendorSpec.ADOPTIUM
        languageVersion = JavaLanguageVersion.of(requiredJava.majorVersion)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = requiredJava.majorVersion.toInt()
}

val modJsonProps = mapOf(
    "mod_version" to property("mod.version") as String,
    "mc_compat" to property("mod.mc_compat") as String,
    "malilib_compat" to property("mod.malilib_compat") as String,
    "litematica_compat" to property("mod.litematica_compat") as String,
)

tasks.processResources {
    val props = modJsonProps
    props.forEach { (k, v) -> inputs.property(k, v) }
    filesMatching("fabric.mod.json") { expand(props) }
}

tasks.test {
    useJUnitPlatform()
}
