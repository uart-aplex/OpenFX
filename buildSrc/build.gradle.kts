plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}

// 本機只有 JRE 8 (無編譯器) — 明確指定用 JDK 17 編譯 buildSrc
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}