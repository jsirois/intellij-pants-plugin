// Copyright 2014 Pants project contributors (see CONTRIBUTORS.md).
// Licensed under the Apache License, Version 2.0 (see LICENSE).

package com.twitter.intellij.pants.service.scala

import java.io.File
import java.util

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.externalSystem.model.{DataNode, ExternalSystemException, ProjectKeys}
import com.intellij.openapi.externalSystem.service.project.manage.ProjectDataService
import com.intellij.openapi.externalSystem.service.project.{PlatformFacade, ProjectStructureHelper}
import com.intellij.openapi.externalSystem.util.{DisposeAwareProjectChange, ExternalSystemApiUtil}
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.libraries.Library
import com.intellij.openapi.vfs.VfsUtilCore
import org.jetbrains.plugins.scala.project._

import scala.collection.JavaConverters._

object PantsScalaDataService {
  val LOG = Logger.getInstance(classOf[PantsScalaDataService])
}

class PantsScalaDataService(platformFacade: PlatformFacade, helper: ProjectStructureHelper)
  extends ProjectDataService[ScalaModelData, Library] {

  import PantsScalaDataService._

  def getTargetDataKey = ScalaModelData.KEY

  final def importData(toImport: util.Collection[DataNode[ScalaModelData]], project: Project, synchronous: Boolean) {
    ExternalSystemApiUtil.executeProjectChangeAction(synchronous, new DisposeAwareProjectChange(project) {
      def execute() {
        doImportData(toImport, project)
      }
    })
  }

  def doImportData(toImport: util.Collection[DataNode[ScalaModelData]], project: Project) {
    toImport.asScala.toSet.foreach[Unit](doImport(_, project))
  }

  private def doImport(scalaNode: DataNode[ScalaModelData], project: Project) {
    val scalaData = scalaNode.getData

    val scalaLibId = scalaData.getScalaLibId

    LOG.debug(s"Setting up $scalaLibId as Scala SDK")

    val compilerVersion =
      scalaLibId.split(":").toSeq.lastOption
      .map(Version(_))
      .getOrElse(throw new ExternalSystemException("Cannot determine Scala compiler version for module " +
                                                   scalaNode.getData(ProjectKeys.MODULE).getExternalName))

    val scalaLibrary = project.libraries.find(_.getName.contains(scalaLibId))
      .getOrElse(throw new ExternalSystemException("Cannot find project Scala library " +
                                                   compilerVersion.number +
                                                   " for module " +
                                                   scalaNode.getData(ProjectKeys.MODULE).getExternalName))

    if (!scalaLibrary.isScalaSdk) {
      val languageLevel = compilerVersion.toLanguageLevel.getOrElse(ScalaLanguageLevel.Default)
      val scalaBinaryJarsPaths = scalaLibrary.getFiles(OrderRootType.CLASSES).toSeq.map(_.getPresentableUrl).map(VfsUtilCore.urlToPath)
      val scalaBinaryJars = scalaBinaryJarsPaths.map(new File(_)).filter(_.exists)
      scalaLibrary.convertToScalaSdkWith(languageLevel, scalaBinaryJars)
    } else {
      LOG.debug(s"${scalaLibrary.getName} is already a Scala SDK")
    }
  }

  final def removeData(toRemove: util.Collection[_ <: Library], project: Project, synchronous: Boolean) {
  }
}
