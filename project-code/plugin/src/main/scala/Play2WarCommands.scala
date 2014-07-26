/*
 * Copyright 2013 Damien Lecan
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.play2war.plugin

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.util.jar.Manifest
import scala.collection.immutable.Stream.consWrapper
import com.github.play2war.plugin.Play2WarKeys._
import sbt.ConfigKey.configurationToKey
import sbt.Keys._
import sbt.Runtime
import sbt.richFile
import sbt.Artifact
import sbt.AttributeKey
import sbt.IO
import sbt.ModuleID
import sbt.Path
import sbt.complete.Parser
import sbt.complete.DefaultParsers._
import sbt.TaskKey
import sbt.Project.Initialize
import sbt.State
import sbt.{`package` => _, _}
import java.io.FilenameFilter

trait Play2WarCommands extends sbt.PlayCommands with sbt.PlayReloader with sbt.PlayPositionMapper {

  val manifestRegex = """(?i).*META-INF/MANIFEST.MF"""

  def getFiles(root: File, skipHidden: Boolean = false): Stream[File] =
    if (!root.exists || (skipHidden && root.isHidden) ||
      manifestRegex.r.pattern.matcher(root.getAbsolutePath()).matches()) {
      Stream.empty
    } else {
      root #:: (
        root.listFiles match {
          case null => Stream.empty
          case files => files.toStream.flatMap(getFiles(_, skipHidden))
        })
    }
      
  val stringInput: Initialize[State => Parser[Option[String]]] = (sbtVersion){ sbtVer =>
		  (state: State) => 
		  val extracted: Extracted = Project.extract(state)
		  import extracted._
		  val confDirectory: Option[sbt.File] = PlayKeys.confDirectory in currentRef get structure.data

		  confDirectory match {	case Some(dir) => dir.listFiles().filter(_.ext == "conf").foldLeft(success(""))( (oldToken, file) => oldToken | (Space ~> token(file.getName())) ).?
		  						case None => success("").?
		  				} 
      } 

  val intermedieteWarTask = (	playPackageEverything, dependencyClasspath in Runtime, Keys.`package` in Compile, explodedJar, servletVersion,
		  						webappResource, disableWarningWhenWebxmlFileFound, targetName, defaultFilteredArtifacts, filteredArtifacts) map {
    (playPackageEverything, dependencyClasspath, packageTaskdependency, explodedJar, servletVersion, webappResource, disableWarningWhenWebxmlFileFound, targetName, defaultFilteredArtifacts, filteredArtifacts) => 
    Play2WarData(playPackageEverything, dependencyClasspath, packageTaskdependency, explodedJar, servletVersion, webappResource, disableWarningWhenWebxmlFileFound, targetName, defaultFilteredArtifacts, filteredArtifacts)
  }
  
  val warTask = (parsedTask: TaskKey[Option[String]]) => {
    
//        ( parsedTask, playPackageEverything, dependencyClasspath in Runtime, target, normalizedName,
//      version, webappResource, streams, servletVersion, targetName, disableWarningWhenWebxmlFileFound, defaultFilteredArtifacts, filteredArtifacts, explodedJar, Keys.`package` in Compile) map {
//    ( selectedConfFileName, packaged, dependencies, target, id, version, webappResource, s, servletVersion, targetName, disableWarningWhenWebxmlFileFound, defaultFilteredArtifacts, filteredArtifacts, explodedJar, pkg) =>
    
    ( parsedTask, intermediateWar, target, normalizedName, version, streams, PlayKeys.confDirectory) map {
    ( selectedConfFileName, play2warData, target, id, version, s, playConfDir) =>

      val configurationFileName = selectedConfFileName.getOrElse("application.conf") 
     
      s.log.info("Build WAR package for servlet container: " + servletVersion)
      s.log.info("Whit configuration file name: " + configurationFileName)
      
      if (play2warData.dependencyClasspath.exists(_.data.name.contains("play2-war-core-common"))) {
        s.log.debug("play2-war-core-common found in dependencies!")
      } else {
        s.log.error("play2-war-core-common not found in dependencies!")
        throw new IllegalArgumentException("play2-war-core-common not found in dependencies!")
      }

      val warDir = target
      val packageName = play2warData.targetName.getOrElse(id + "-" + version)
      val war = warDir / (packageName + ".war")
      val manifestString = "Manifest-Version: 1.0\n"

      s.log.info("Packaging " + war.getCanonicalPath + " ...")

      IO.createDirectory(warDir)

      val allFilteredArtifacts = play2warData.defaultFilteredArtifacts ++ play2warData.filteredArtifacts

      allFilteredArtifacts.foreach {
        case (groupId, artifactId) =>
          s.log.debug("Ignoring dependency " + groupId + " -> " + artifactId)
      }

      val files: Traversable[(File, String)] = play2warData.dependencyClasspath.
        filter(_.data.ext == "jar").flatMap { dependency =>
          val filename = for {
            module <- dependency.metadata.get(AttributeKey[ModuleID]("module-id"))
            artifact <- dependency.metadata.get(AttributeKey[Artifact]("artifact"))
            if (!allFilteredArtifacts.contains((module.organization, module.name)))
          } yield {
            // groupId.artifactId-version[-classifier].extension
            module.organization + "." + module.name + "-" + module.revision + artifact.classifier.map("-" + _).getOrElse("") + "." + artifact.extension
          }
          filename.map { fName =>
            val path = ("WEB-INF/lib/" + fName)
            Some(dependency.data -> path)
          }.getOrElse(None)
      } ++ {
        /////////////////////////////////////////////////////////////////////
        if(configurationFileName!="application.conf"){
          val explodedJarTmpDir = target / "explodedTmp"
          IO.delete(explodedJarTmpDir)
          IO.createDirectory(explodedJarTmpDir)

          play2warData.playPackageEverything.map { jar =>
            val filesToRezip :Traversable[(java.io.File, String)] = 
            IO.unzip(jar, explodedJarTmpDir).map { file =>
            	val partialPath = IO.relativize(explodedJarTmpDir, file).getOrElse(file.getName)
                val absolutePath = file.getPath

                if(absolutePath.contains(playConfDir.getName())
                    && absolutePath.contains("application.conf")){
            		file -> (partialPath+".unusedPlay2War")
            	}else if(absolutePath.contains(playConfDir.getName()) 
            			  && absolutePath.contains(configurationFileName)){
            		file -> ("application.conf")
            	}else{
            		file -> (partialPath)
            	}
            }
            IO.zip(filesToRezip, jar)
            jar
          }
        }
        /////////////////////////////////////////////////////////////////////
        if (play2warData.explodedJar) {
           s.log.info("Main artifacts " + play2warData.playPackageEverything.map(_.getName).mkString("'", " ", "'") + " will be packaged exploded")

          val explodedJarDir = target / "exploded"
          
          IO.delete(explodedJarDir)
          IO.createDirectory(explodedJarDir)

          play2warData.playPackageEverything.flatMap { jar =>
            IO.unzip(jar, explodedJarDir).map {
              file =>
                val partialPath = IO.relativize(explodedJarDir, file).getOrElse(file.getName)
                
                file -> ("WEB-INF/classes/" + partialPath)
            }
          }
        } else play2warData.playPackageEverything.map(jar => jar -> ("WEB-INF/lib/" + jar.getName))
      }
      
      files.foreach { case (file, path) =>
        s.log.debug("Embedding file " + file + " -> " + path)
      }

      val webxmlFolder = play2warData.webappResource / "WEB-INF"
      val webxml = webxmlFolder / "web.xml"

      // Web.xml generation
      play2warData.servletVersion match {
        case "2.5" => {

          if (webxml.exists) {
            s.log.info("WEB-INF/web.xml found.")
          } else {
            s.log.info("WEB-INF/web.xml not found, generate it in " + webxmlFolder)
            IO.write(webxml,
              """<?xml version="1.0" ?>
<web-app xmlns="http://java.sun.com/xml/ns/javaee"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://java.sun.com/xml/ns/javaee http://java.sun.com/xml/ns/javaee/web-app_2_5.xsd"
        version="2.5">

  <display-name>Play! """ + id + """</display-name>

  <listener>
      <listener-class>play.core.server.servlet25.Play2Servlet</listener-class>
  </listener>

  <servlet>
    <servlet-name>play</servlet-name>
    <servlet-class>play.core.server.servlet25.Play2Servlet</servlet-class>
  </servlet>

  <servlet-mapping>
    <servlet-name>play</servlet-name>
    <url-pattern>/</url-pattern>
  </servlet-mapping>

</web-app>
                                 """ /* */)
          }

        }

        case "3.0" => handleWebXmlFileOnServlet30(webxml, s, play2warData.disableWarningWhenWebxmlFileFound)
        
        case unknown => {
            s.log.warn("Unknown servlet container version: " + unknown + ". Force default 3.0 version")
            handleWebXmlFileOnServlet30(webxml, s, play2warData.disableWarningWhenWebxmlFileFound)
        }
      }

      // Webapp resources
      s.log.debug("Webapp resources directory: " + play2warData.webappResource.getAbsolutePath)

      val filesToInclude = getFiles(play2warData.webappResource).filter(f => f.isFile)

      val additionnalResources = filesToInclude.map {
        f =>
          f -> Path.relativizeFile(play2warData.webappResource, f).get.getPath
      }

      additionnalResources.foreach {
        r =>
          s.log.debug("Embedding " + r._1 + " -> /" + r._2)
      }

      val metaInfFolder = play2warData.webappResource / "META-INF"
      val manifest = if (metaInfFolder.exists()) {
        val option = metaInfFolder.listFiles.find(f =>
          manifestRegex.r.pattern.matcher(f.getAbsolutePath()).matches())
        if (option.isDefined) {
          new Manifest(new FileInputStream(option.get))
        }
        else {
          new Manifest(new ByteArrayInputStream(manifestString.getBytes))
        }
      }
      else {
        new Manifest(new ByteArrayInputStream(manifestString.getBytes))
      }

      // Package final jar
      val jarContent = files ++ additionnalResources

      IO.jar(jarContent, war, manifest)

      s.log.info("Packaging done.")

      war
  }

    
  }
    
    
  def handleWebXmlFileOnServlet30(webxml: File, s: TaskStreams, disableWarn: Boolean) = {
    if (webxml.exists && !disableWarn) {
      s.log.warn("WEB-INF/web.xml found! As WAR package will be built for servlet 3.0 containers, check if this web.xml file is compatible with.")
    }
  }
}
