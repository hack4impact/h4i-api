plugins {
	kotlin("jvm") version "2.3.21"
	kotlin("plugin.spring") version "2.3.21"
	id("org.springframework.boot") version "4.1.1"
	id("io.spring.dependency-management") version "1.1.7"
	jacoco
}

group = "org.hack4impact"
version = "0.0.1-SNAPSHOT"
description = "H4I member portal, stage 1: members, approvals, claims and joining"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(25)
	}
}

repositories {
	mavenCentral()
}

// Spring Modulith (event outbox, wiki decision 33) isn't managed by Spring Boot's BOM.
dependencyManagement {
	imports {
		mavenBom("org.springframework.modulith:spring-modulith-bom:2.1.1")
	}
}

// Generates jOOQ classes from the Flyway migrations: starts Postgres in Testcontainers, migrates, reads the schema.
val codegen = sourceSets.create("codegen")

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.springframework.boot:spring-boot-starter-jooq")
	implementation("org.springframework.boot:spring-boot-starter-mail")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.boot:spring-boot-starter-security-oauth2-client")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.flywaydb:flyway-database-postgresql")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("tools.jackson.module:jackson-module-kotlin")
	implementation("com.google.auth:google-auth-library-oauth2-http:1.54.0")
	implementation("org.springdoc:springdoc-openapi-starter-webmvc-api:3.1.1")
	implementation("org.springframework.modulith:spring-modulith-starter-jdbc")
	developmentOnly("org.springframework.boot:spring-boot-docker-compose")
	runtimeOnly("org.postgresql:postgresql")
	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
	testImplementation("org.springframework.boot:spring-boot-starter-jooq-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-oauth2-client-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testImplementation("org.testcontainers:testcontainers-junit-jupiter")
	testImplementation("org.testcontainers:testcontainers-postgresql")
	testImplementation("net.jqwik:jqwik:1.10.1")
	testImplementation("net.jqwik:jqwik-kotlin:1.10.1")
	testImplementation("org.wiremock:wiremock-standalone:3.13.2")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")

	"codegenImplementation"("org.jooq:jooq-codegen")
	"codegenImplementation"("org.flywaydb:flyway-core")
	"codegenImplementation"("org.flywaydb:flyway-database-postgresql")
	"codegenImplementation"("org.testcontainers:testcontainers-postgresql")
	"codegenRuntimeOnly"("org.postgresql:postgresql")
	"codegenRuntimeOnly"("org.slf4j:slf4j-nop")
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
	}
}

val migrations = layout.projectDirectory.dir("src/main/resources/db/migration")
val jooqOutput = layout.buildDirectory.dir("generated/jooq")

val generateJooq = tasks.register<JavaExec>("generateJooq") {
	group = "build"
	description = "Generates jOOQ classes from the Flyway migrations (needs Docker)."
	classpath = codegen.runtimeClasspath
	mainClass = "org.hack4impact.portal.codegen.JooqCodegenKt"
	inputs.dir(migrations)
	outputs.dir(jooqOutput)
	args(migrations.asFile.absolutePath, jooqOutput.get().asFile.absolutePath)
}

kotlin.sourceSets.named("main") {
	kotlin.srcDir(jooqOutput)
}

tasks.named("compileKotlin") {
	dependsOn(generateJooq)
}

tasks.withType<Test> {
	useJUnitPlatform()
	// -PupdateApiSpec rewrites openapi.json from the live API (see OpenApiSpecTests).
	systemProperty("updateApiSpec", providers.gradleProperty("updateApiSpec").isPresent.toString())
}

// Only the executable Spring Boot jar is needed; the Dockerfile packages it.
tasks.named<Jar>("jar") {
	enabled = false
}

jacoco {
	toolVersion = "0.8.15"
}

tasks.named<Test>("test") {
	finalizedBy(tasks.named("jacocoTestReport"))
}

tasks.named<JacocoReport>("jacocoTestReport") {
	reports {
		xml.required = true
		html.required = true
	}
}

// `./gradlew bootRun -PenvFile=<file>` loads NAME="value" lines, the format deploy/fetch-secrets.sh writes
// (JSON-quoted, so multi-line keys survive), into the app's environment.
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
	providers.gradleProperty("envFile").orNull?.let { path ->
		file(path).readLines().filter { '=' in it && !it.trimStart().startsWith("#") }.forEach { line ->
			val (name, raw) = line.split("=", limit = 2)
			environment(name.trim(), if (raw.startsWith("\"")) groovy.json.JsonSlurper().parseText(raw) as String else raw)
		}
	}
}
