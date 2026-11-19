plugins { kotlin("jvm"); kotlin("plugin.serialization"); application }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
application { mainClass.set("kr.co.akpda.server.MainKt") }
dependencies { implementation(project(":shared")); implementation("com.microsoft.sqlserver:mssql-jdbc:12.8.1.jre11"); testImplementation(kotlin("test-junit5")); testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.4"); testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4") }
tasks.test { useJUnitPlatform() }
