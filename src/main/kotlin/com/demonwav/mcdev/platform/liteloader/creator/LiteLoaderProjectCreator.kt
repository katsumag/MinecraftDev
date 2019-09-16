/*
 * Minecraft Dev for IntelliJ
 *
 * https://minecraftdev.org
 *
 * Copyright (c) 2020 minecraft-dev
 *
 * MIT License
 */

package com.demonwav.mcdev.platform.liteloader.creator

import com.demonwav.mcdev.creator.BaseProjectCreator
import com.demonwav.mcdev.creator.BasicJavaClassStep
import com.demonwav.mcdev.creator.CreatorStep
import com.demonwav.mcdev.creator.buildsystem.gradle.BasicGradleFinalizerStep
import com.demonwav.mcdev.creator.buildsystem.gradle.GradleBuildSystem
import com.demonwav.mcdev.creator.buildsystem.gradle.GradleFiles
import com.demonwav.mcdev.creator.buildsystem.gradle.GradleWrapperStep
import com.demonwav.mcdev.platform.forge.creator.FgSetupStep
import com.demonwav.mcdev.platform.forge.creator.SetupDecompWorkspaceStep
import com.intellij.openapi.module.Module
import java.nio.file.Path

class LiteLoaderProjectCreator(
    private val rootDirectory: Path,
    private val rootModule: Module,
    private val buildSystem: GradleBuildSystem,
    private val config: LiteLoaderProjectConfig
) : BaseProjectCreator(rootModule, buildSystem) {

    private fun setupMainClassStep(): BasicJavaClassStep {
        return createJavaClassStep(config.mainClass) { packageName, className ->
            val modName = config.pluginName
            LiteLoaderTemplate.applyMainClass(project, packageName, className, modName, buildSystem.version)
        }
    }

    override fun getSingleModuleSteps(): Iterable<CreatorStep> {
        val buildText = LiteLoaderTemplate.applyBuildGradle(project, buildSystem, config.mcVersion)
        val propText = LiteLoaderTemplate.applyGradleProp(project, config)
        val settingsText = LiteLoaderTemplate.applySettingsGradle(project, buildSystem.artifactId)
        val files = GradleFiles(buildText, propText, settingsText)

        return listOf(
            FgSetupStep(project, rootDirectory, buildSystem, files),
            setupMainClassStep(),
            GradleWrapperStep(project, rootDirectory, buildSystem),
            SetupDecompWorkspaceStep(project, rootDirectory),
            BasicGradleFinalizerStep(rootModule, rootDirectory, buildSystem)
        )
    }

    override fun getMultiModuleSteps(projectBaseDir: Path): Iterable<CreatorStep> {
        val buildText = LiteLoaderTemplate.applySubBuildGradle(project, buildSystem, config.mcVersion)
        val propText = LiteLoaderTemplate.applyGradleProp(project, config)
        val files = GradleFiles(buildText, propText, null)

        return listOf(
            FgSetupStep(project, rootDirectory, buildSystem, files),
            setupMainClassStep(),
            SetupDecompWorkspaceStep(project, rootDirectory)
        )
    }
}
