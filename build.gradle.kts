plugins {
    `java-library`
    `maven-publish`
    id("com.google.protobuf") version "0.9.5"
}

group = "io.github.tursom"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
    withJavadocJar()
    withSourcesJar()
}

repositories {
    mavenCentral()
}

dependencies {
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api("org.mindrot:jbcrypt:0.4")

    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    implementation("com.google.protobuf:protobuf-java:4.29.3")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

sourceSets {
    main {
        proto {
            srcDir("proto")
            include("client.proto")
            include("relay.proto")
        }
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.29.3"
    }
}

tasks.test {
    useJUnitPlatform()
}
