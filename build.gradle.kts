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
    // malilib / litematica (sakura-ryoko fork) は Modrinth maven から取る。
    // masa maven (masa.dy.fi/maven/sakura-ryoko) は系列ごとに最新の数版しか残さず、固定した版が消えて
    // ビルドが壊れるため (docs/notes/multi-version.md 2026-11-03)。
    // 座標は maven.modrinth:<slug>:<version_number>。0.26 以降の版番号は MC 系列をまたいで重複しないので、
    // version_number だけで系列ごとの jar に一意に決まる。
    strictMaven("https://api.modrinth.com/maven", "Modrinth", "maven.modrinth")
    // conditional-mixin (deps.dev_runtime 用。malilib/litematica に同梱されているものと同じ版)
    strictMaven("https://maven.fallenbreath.me/releases", "FallenBreath", "me.fallenbreath")
}

dependencies {
    minecraft("com.mojang:minecraft:${sc.current.version}")
    // obfuscated なバージョン (1.21.x) にだけ Mojang mappings を適用する
    loomx.applyMojangMappings()

    modImplementation("net.fabricmc:fabric-loader:${property("deps.fabric_loader")}")
    implementation("com.google.code.findbugs:jsr305:3.0.2")

    // 依存 mod。Litematica 本体の materials/json サブシステムを呼ぶためコンパイル依存する。
    // mod* は 26.1+ では loom-back-compat が plain 構成へ変換する。
    modImplementation("maven.modrinth:malilib:${property("deps.malilib")}")
    modImplementation("maven.modrinth:litematica:${property("deps.litematica")}")
    // malilib が同梱 (JiJ) している fabric-api モジュール等。remap する系列 (1.21.x) では loom が
    // 依存 mod の fabric.mod.json から jars を外すため、同梱分が dev 環境で読まれない。masa maven の POM は
    // これらを推移的依存に書いていたが Modrinth maven の POM には無いので、dev 実行用に明示する。
    // remap しない系列 (26.1+) は同梱分がそのまま読まれるので不要。
    findProperty("deps.dev_runtime")?.toString()?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)
        ?.forEach { modLocalRuntime(it) }

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
