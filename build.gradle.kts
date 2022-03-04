plugins {
    java
    application
    distribution
}

application {
    mainClass.set("com.github.dimiro1.mynes.ui.Main")
}

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("org.slf4j:slf4j-api:1.7.36")
    implementation("org.slf4j:slf4j-simple:1.7.36")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.8.2")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.8.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.8.2")
}

tasks.test {
    useJUnitPlatform()
}

group = "com.github.dimiro1"
version = "1.0-SNAPSHOT"
description = "mynes"
java.sourceCompatibility = JavaVersion.VERSION_17
