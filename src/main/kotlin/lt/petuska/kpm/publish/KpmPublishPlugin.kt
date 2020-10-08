package lt.petuska.kpm.publish

import lt.petuska.kpm.publish.dsl.KpmPublishExtension
import lt.petuska.kpm.publish.dsl.KpmPublishExtension.Companion.EXTENSION_NAME
import lt.petuska.kpm.publish.task.KpmPackagePrepareTask
import lt.petuska.kpm.publish.task.KpmPublishTask
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Copy
import org.gradle.util.GUtil
import org.jetbrains.kotlin.gradle.dsl.KotlinJsProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinJsCompilation
import org.jetbrains.kotlin.gradle.targets.js.KotlinJsTarget
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsSetupTask
import org.jetbrains.kotlin.gradle.targets.js.npm.NpmDependency

class KpmPublishPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    project.createExtension()
    project.pluginManager.withPlugin(KOTLIN_MPP_PLUGIN) {
      project.afterEvaluate {
        project.extensions.configure(KotlinMultiplatformExtension::class.java) {
          it.targets.filterIsInstance<KotlinJsTarget>().forEach { t ->
            project.configureExtension(t.name, t.compilations, true)
          }
          project.configureTasks()
        }
      }
    }
    project.pluginManager.withPlugin(KOTLIN_JS_PLUGIN) {
      project.afterEvaluate {
        project.extensions.configure(KotlinJsProjectExtension::class.java) {
          val target = it.js()
          project.configureExtension(target.name, target.compilations, false)
          project.configureTasks()
        }
      }
    }
  }

  companion object {
    private const val KOTLIN_JS_PLUGIN = "org.jetbrains.kotlin.js"
    private const val KOTLIN_MPP_PLUGIN = "org.jetbrains.kotlin.multiplatform"

    private fun Project.createExtension() = extensions.findByType(KpmPublishExtension::class.java) ?: extensions.create(
      EXTENSION_NAME,
      KpmPublishExtension::class.java,
      this@createExtension
    )

    private fun Project.configureExtension(targetName: String, compilations: NamedDomainObjectContainer<out KotlinJsCompilation>, mpp: Boolean) {
      val compilation = compilations.first { comp -> comp.name.contains("main", true) }
      val deps = compilation.relatedConfigurationNames.flatMap { conf ->
        val mainName = "${targetName}Main${conf.substringAfter(targetName)}"
        val normDeps = configurations.findByName(conf)?.dependencies?.toSet() ?: setOf()
        val mainDeps = configurations.findByName(mainName)?.dependencies?.toSet() ?: setOf()
        (normDeps + mainDeps).filterIsInstance<NpmDependency>()
      }

      kpmPublish {
        publications {
          publication(targetName) {
            this.compilation = compilation
            this.main = compilation.compileKotlinTask.outputFile.name
            dependencies {
              addAll(deps)
            }
          }
        }
      }
    }

    private fun Project.configureTasks() {
      val nodeJsSetupTask = tasks.findByName("kotlinNodeJsSetup") as NodeJsSetupTask?
      val publishTask = tasks.findByName("publish")

      val publications = kpmPublish.publications.mapNotNull { pub ->
        val needsNode = pub.nodeJsDir == null
        pub.validate(nodeJsSetupTask?.destination)?.let { it to nodeJsSetupTask?.takeIf { needsNode } }
      }
      val repositories = kpmPublish.repositories.mapNotNull { repo ->
        repo.validate()
      }

      publications.flatMap { (pub, nodeJsTask) ->
        pub.compilation?.let {
          val (processResourcesTask, compileKotlinTask) = project.tasks.findByName(it.processResourcesTaskName) as Copy to it.compileKotlinTask
          pub.files {
            from(compileKotlinTask.outputFile.parentFile)
            from(processResourcesTask.destinationDir)
          }
        }
        val upperName = GUtil.toCamelCase(pub.name)

        val packagePrepareTask =
          tasks.register("assemble${upperName}KpmPublication", KpmPackagePrepareTask::class.java, pub)
        packagePrepareTask.configure {
          it.dependsOn(
            *listOfNotNull(
              pub.compilation?.processResourcesTaskName,
              pub.compilation?.compileKotlinTaskName,
              nodeJsTask
            ).toTypedArray()
          )
        }
        repositories.map { repo ->
          val upperRepoName = GUtil.toCamelCase(repo.name)
          tasks.register("publish${upperName}KpmPublicationTo$upperRepoName", KpmPublishTask::class.java, pub, repo).also { task ->
            task.configure {
              it.dependsOn(packagePrepareTask)
            }
            publishTask?.dependsOn(task)
          }
        }
      }
    }
  }
}

internal val Project.kpmPublish: KpmPublishExtension
  get() = extensions.getByName(EXTENSION_NAME) as? KpmPublishExtension
    ?: throw IllegalStateException("$EXTENSION_NAME is not of the correct type")

internal fun Project.kpmPublish(config: KpmPublishExtension.() -> Unit = {}): KpmPublishExtension =
  kpmPublish.apply(config)
