plugins {
  id("lt.petuska.npm.publish") version "0.0.5"
  kotlin("multiplatform") version "1.4.10"
  `maven-publish`
}

version = "1.0.0"
group = "test.group"


repositories {
  jcenter()
  mavenCentral()
}

kotlin {
  js { browser() }

  sourceSets {
    val jsMain by getting {
      dependencies {
        implementation(npm("axios", "*"))
        api(npm("snabbdom", "*"))
      }
    }
  }
}

npmPublishing {
  repositories {
    repository("GitLab") {
      registry = uri("https://gitlab.com/api/v4/projects/${System.getenv("CI_PROJECT_ID")?.trim()}/packages/npm")
      authToken = System.getenv("PRIVATE_TOKEN")?.trim() ?: ""
    }
  }
}
