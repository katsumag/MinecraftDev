/*
 * Minecraft Dev for IntelliJ
 *
 * https://minecraftdev.org
 *
 * Copyright (c) 2020 minecraft-dev
 *
 * MIT License
 */

package com.demonwav.mcdev.platform.sponge.creator

import com.demonwav.mcdev.creator.BaseProjectCreator
import com.demonwav.mcdev.creator.CreateDirectoriesStep
import com.demonwav.mcdev.creator.CreatorStep
import com.demonwav.mcdev.creator.buildsystem.BuildDependency
import com.demonwav.mcdev.creator.buildsystem.BuildRepository
import com.demonwav.mcdev.creator.buildsystem.BuildSystem
import com.demonwav.mcdev.creator.buildsystem.BuildSystemType
import com.demonwav.mcdev.creator.buildsystem.gradle.BasicGradleFinalizerStep
import com.demonwav.mcdev.creator.buildsystem.gradle.BasicGradleStep
import com.demonwav.mcdev.creator.buildsystem.gradle.GradleBuildSystem
import com.demonwav.mcdev.creator.buildsystem.gradle.GradleFiles
import com.demonwav.mcdev.creator.buildsystem.gradle.GradleWrapperStep
import com.demonwav.mcdev.creator.buildsystem.maven.BasicMavenFinalizerStep
import com.demonwav.mcdev.creator.buildsystem.maven.BasicMavenStep
import com.demonwav.mcdev.creator.buildsystem.maven.CommonModuleDependencyStep
import com.demonwav.mcdev.creator.buildsystem.maven.MavenBuildSystem
import com.demonwav.mcdev.util.runWriteAction
import com.demonwav.mcdev.util.runWriteTask
import com.demonwav.mcdev.util.virtualFileOrError
import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.codeStyle.CodeStyleManager
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.EnumSet

sealed class SpongeProjectCreator<T : BuildSystem>(
    protected val rootDirectory: Path,
    protected val rootModule: Module,
    protected val buildSystem: T,
    protected val config: SpongeProjectConfig
) : BaseProjectCreator(rootModule, buildSystem) {

    protected fun setupDependencyStep(): SpongeDependenciesSetup {
        val spongeApiVersion = config.spongeApiVersion
        return SpongeDependenciesSetup(buildSystem, spongeApiVersion)
    }

    protected fun setupMainClassSteps(): Pair<CreatorStep, CreatorStep> {
        val mainClassStep = createJavaClassStep(config.mainClass) { packageName, className ->
            SpongeTemplate.applyMainClass(project, packageName, className, config.hasDependencies())
        }

        val (packageName, className) = splitPackage(config.mainClass)
        return mainClassStep to SpongeMainClassModifyStep(project, buildSystem, packageName, className, config)
    }
}

class SpongeMavenCreator(
    rootDirectory: Path,
    rootModule: Module,
    buildSystem: MavenBuildSystem,
    config: SpongeProjectConfig
) : SpongeProjectCreator<MavenBuildSystem>(rootDirectory, rootModule, buildSystem, config) {

    override fun getSingleModuleSteps(): Iterable<CreatorStep> {
        val (mainClassStep, modifyStep) = setupMainClassSteps()

        val pomText = SpongeTemplate.applyPom(project)

        return listOf(
            setupDependencyStep(),
            BasicMavenStep(project, rootDirectory, buildSystem, config, pomText),
            mainClassStep,
            modifyStep,
            BasicMavenFinalizerStep(rootModule, rootDirectory)
        )
    }

    override fun getMultiModuleSteps(projectBaseDir: Path): Iterable<CreatorStep> {
        val depStep = setupDependencyStep()
        val commonDepStep = CommonModuleDependencyStep(buildSystem)
        val (mainClassStep, modifyStep) = setupMainClassSteps()

        val pomText = SpongeTemplate.applySubPom(project)
        val mavenStep = BasicMavenStep(
            project = project,
            rootDirectory = rootDirectory,
            buildSystem = buildSystem,
            config = config,
            pomText = pomText,
            parts = listOf(
                BasicMavenStep.setupDirs(),
                BasicMavenStep.setupSubCore(buildSystem.parentOrError.artifactId),
                BasicMavenStep.setupSubName(config.type),
                BasicMavenStep.setupInfo(),
                BasicMavenStep.setupDependencies()
            )
        )
        return listOf(depStep, commonDepStep, mavenStep, mainClassStep, modifyStep)
    }
}

class SpongeGradleCreator(
    rootDirectory: Path,
    rootModule: Module,
    buildSystem: GradleBuildSystem,
    config: SpongeProjectConfig
) : SpongeProjectCreator<GradleBuildSystem>(rootDirectory, rootModule, buildSystem, config) {

    override fun getSingleModuleSteps(): Iterable<CreatorStep> {
        val (mainClassStep, modifyStep) = setupMainClassSteps()

        val buildText = SpongeTemplate.applyBuildGradle(project, buildSystem)
        val propText = SpongeTemplate.applyGradleProp(project)
        val settingsText = SpongeTemplate.applySettingsGradle(project, buildSystem.artifactId)
        val files = GradleFiles(buildText, propText, settingsText)

        return listOf(
            setupDependencyStep(),
            CreateDirectoriesStep(buildSystem, rootDirectory),
            BasicGradleStep(project, rootDirectory, buildSystem, files),
            mainClassStep,
            modifyStep,
            GradleWrapperStep(project, rootDirectory, buildSystem),
            BasicGradleFinalizerStep(rootModule, rootDirectory, buildSystem)
        )
    }

    override fun getMultiModuleSteps(projectBaseDir: Path): Iterable<CreatorStep> {
        val (mainClassStep, modifyStep) = setupMainClassSteps()

        val buildText = SpongeTemplate.applySubBuildGradle(project, buildSystem)
        val files = GradleFiles(buildText, null, null)

        return listOf(
            setupDependencyStep(),
            CreateDirectoriesStep(buildSystem, rootDirectory),
            BasicGradleStep(project, rootDirectory, buildSystem, files),
            mainClassStep,
            modifyStep
        )
    }
}

class SpongeMainClassModifyStep(
    private val project: Project,
    private val buildSystem: BuildSystem,
    private val packageName: String,
    private val className: String,
    private val config: SpongeProjectConfig
) : CreatorStep {
    override fun runStep(indicator: ProgressIndicator) {
        val dirs = buildSystem.dirsOrError

        runWriteTask {
            val targetDir = dirs.sourceDirectory.resolve(Paths.get(packageName.replace('.', '/')))
            if (!Files.isDirectory(targetDir)) {
                throw IllegalStateException("$targetDir is not an existing directory")
            }
            val javaFile = targetDir.resolve("$className.java")
            if (!Files.isRegularFile(javaFile)) {
                throw IllegalStateException("$javaFile is not an existing file")
            }

            val psiFile = PsiManager.getInstance(project).findFile(javaFile.virtualFileOrError) as? PsiJavaFile
                ?: throw IllegalStateException("Failed to resolve PsiJavaFile for $javaFile")
            val psiClass = psiFile.classes[0]

            val annotationString = StringBuilder("@Plugin(")
            annotationString + "\nid = ${escape(buildSystem.artifactId.toLowerCase())}"
            annotationString + ",\nname = ${escape(config.pluginName)}"
            if (buildSystem.type != BuildSystemType.GRADLE) {
                // SpongeGradle will automatically set the Gradle version as plugin version
                annotationString + ",\nversion = ${escape(buildSystem.version)}"
            }

            if (config.hasDescription()) {
                annotationString + ",\ndescription = ${escape(config.description)}"
            }

            if (config.hasWebsite()) {
                annotationString + ",\nurl = ${escape(config.website)}"
            }

            if (config.hasAuthors()) {
                annotationString + ",\nauthors = {\n${config.authors.joinToString(",\n", transform = ::escape)}\n}"
            }

            if (config.hasDependencies()) {
                val dep = config.dependencies.joinToString(",\n") { "@Dependency(id = ${escape(it)})" }
                annotationString + ",\ndependencies = {\n$dep\n}"
            }

            annotationString + "\n)"
            val factory = JavaPsiFacade.getElementFactory(project)
            val annotation = factory.createAnnotationFromText(annotationString.toString(), null)

            psiFile.runWriteAction {
                psiClass.modifierList?.let { modifierList ->
                    modifierList.addBefore(annotation, modifierList.firstChild)
                }
                CodeStyleManager.getInstance(project).reformat(psiClass)
            }
        }
    }

    private fun escape(text: String?): String {
        if (text == null) {
            return "\"\""
        }
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }

    private operator fun StringBuilder.plus(text: String) = this.append(text)
}

class SpongeDependenciesSetup(
    private val buildSystem: BuildSystem,
    private val spongeApiVersion: String
) : CreatorStep {
    override fun runStep(indicator: ProgressIndicator) {
        buildSystem.repositories.add(
            BuildRepository(
                "spongepowered-repo",
                "https://repo.spongepowered.org/maven/",
                buildSystems = EnumSet.of(BuildSystemType.MAVEN)
            )
        )
        buildSystem.dependencies.add(
            BuildDependency(
                "org.spongepowered",
                "spongeapi",
                spongeApiVersion,
                mavenScope = "provided",
                gradleConfiguration = "compileOnly"
            )
        )
        buildSystem.dependencies.add(
            BuildDependency(
                "org.spongepowered",
                "spongeapi",
                spongeApiVersion,
                gradleConfiguration = "annotationProcessor"
            )
        )
    }
}
