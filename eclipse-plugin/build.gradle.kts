import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

plugins {
    java
}

val eclipseHome = file(providers.gradleProperty("eclipseHome").get())
// p2 는 버전이 올라가야 업데이트로 인식하므로 빌드마다 시각을 꼬리표로 붙인다. 예: 0.1.0.v202611041530
val qualifier = providers.gradleProperty("buildQualifier").getOrElse(
    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmm")))
val fullVersion = "${providers.gradleProperty("pluginVersion").get()}.v$qualifier"
version = fullVersion

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // eGovFrame 3.x 개발환경(Eclipse 4.8)은 Java 8 로 실행된다.
    options.release = 8
    options.compilerArgs.add("-Xlint:-options")
}

repositories {
    mavenCentral()
}

val bundled: Configuration by configurations.creating

dependencies {
    // 0.22 부터 Java 11 이 필요해서 Java 8 에서 도는 마지막 버전을 쓴다.
    bundled("org.commonmark:commonmark:0.21.0")
    bundled("org.commonmark:commonmark-ext-gfm-tables:0.21.0")
    compileOnly(fileTree(eclipseHome.resolve("plugins")) { include("*.jar") })
}
configurations.compileClasspath { extendsFrom(bundled) }

// ---------------------------------------------------------------- 플러그인 jar

tasks.jar {
    archiveFileName = "kr.egovclaude.eclipse_$fullVersion.jar"
    manifest {
        from("META-INF/MANIFEST.MF")
        attributes("Bundle-Version" to fullVersion)
    }
    from("plugin.xml", "about.html")
    from("icons") { into("icons") }
    from(bundled) {
        into("lib")
        rename("""commonmark-ext-gfm-tables-.*[.]jar""", "commonmark-ext-gfm-tables.jar")
        rename("""commonmark-[0-9.]+[.]jar""", "commonmark.jar")
    }
    // commonmark 의 BSD 라이선스 전문을 about_files 에 함께 넣는다.
    from({ bundled.filter { it.name.startsWith("commonmark-0") }.map { zipTree(it) } }) {
        include("META-INF/LICENSE.txt")
        eachFile { path = "about_files/commonmark-LICENSE.txt" }
        includeEmptyDirs = false
    }
}

tasks.register<Copy>("installDropins") {
    description = "빌드한 플러그인을 eGovFrame 개발환경의 dropins 폴더에 복사합니다. (개발 중 빠른 확인용)"
    doFirst {
        // 같은 플러그인의 다른 버전이 남아 있으면 Eclipse 가 둘 다 읽으려다 충돌한다.
        eclipseHome.resolve("dropins").listFiles { f -> f.name.startsWith("kr.egovclaude.eclipse_") }?.forEach { it.delete() }
    }
    from(tasks.jar)
    into(eclipseHome.resolve("dropins"))
}

// ---------------------------------------------------------------- 업데이트 사이트

val featureJar = tasks.register<Jar>("featureJar") {
    description = "설치 단위가 되는 feature jar 를 만듭니다."
    archiveFileName = "kr.egovclaude.eclipse.feature_$fullVersion.jar"
    destinationDirectory = layout.buildDirectory.dir("feature")
    from("feature") {
        filter { it.replace("@VERSION@", fullVersion) }
    }
}

val siteSource = layout.buildDirectory.dir("site-source")
val siteDir = layout.buildDirectory.dir("update-site")

val stageSite = tasks.register<Sync>("stageSite") {
    from(tasks.jar) { into("plugins") }
    from(featureJar) { into("features") }
    from("site/category.xml") { filter { it.replace("@VERSION@", fullVersion) } }
    into(siteSource)
}

/** eGovFrameDev 에 들어 있는 p2 도구를 화면 없이 실행한다. */
fun p2(vararg args: String): List<String> = listOf(
    eclipseHome.resolve("eclipsec.exe").absolutePath,
    "-nosplash", "-consoleLog",
    "-data", layout.buildDirectory.dir("p2-workspace").get().asFile.absolutePath,
) + args

val updateSite = tasks.register("updateSite") {
    group = "distribution"
    description = "build/update-site 에 p2 업데이트 사이트를, build/distributions 에 그 zip 을 만듭니다."
    dependsOn(stageSite)
    inputs.dir(siteSource)
    outputs.dir(siteDir)
    doLast {
        val site = siteDir.get().asFile
        val source = siteSource.get().asFile
        site.deleteRecursively()
        val repo = site.toURI().toString()
        providers.exec {
            commandLine(p2(
                "-application", "org.eclipse.equinox.p2.publisher.FeaturesAndBundlesPublisher",
                "-metadataRepository", repo, "-metadataRepositoryName", "eGov Claude",
                "-artifactRepository", repo, "-artifactRepositoryName", "eGov Claude",
                "-source", source.absolutePath,
                "-publishArtifacts", "-compress"))
        }.result.get().assertNormalExitValue()
        providers.exec {
            commandLine(p2(
                "-application", "org.eclipse.equinox.p2.publisher.CategoryPublisher",
                "-metadataRepository", repo,
                "-categoryDefinition", source.resolve("category.xml").toURI().toString(),
                "-categoryQualifier", "kr.egovclaude", "-compress"))
        }.result.get().assertNormalExitValue()
        check(site.resolve("content.jar").isFile) { "업데이트 사이트 생성에 실패했습니다: $site" }
    }
}

tasks.register<Sync>("publishSite") {
    group = "distribution"
    description = "업데이트 사이트를 저장소의 docs/update-site 로 복사합니다. 커밋·푸시하면 GitHub Pages 로 배포됩니다."
    dependsOn(updateSite)
    from(siteDir)
    into(rootDir.resolve("../docs/update-site"))
}

tasks.register<Zip>("updateSiteZip") {
    group = "distribution"
    description = "업데이트 사이트를 zip 하나로 묶습니다. Install New Software > Add > Archive 로 바로 설치할 수 있습니다."
    dependsOn(updateSite)
    from(siteDir)
    archiveFileName = "egov-claude-update-site-$fullVersion.zip"
    destinationDirectory = layout.buildDirectory.dir("distributions")
}
