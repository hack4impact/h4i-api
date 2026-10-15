plugins {
	// Downloads the JDK named by the toolchain in build.gradle.kts when it isn't installed locally.
	id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "portal"
