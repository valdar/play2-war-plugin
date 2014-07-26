package com.github.play2war.plugin

case class Play2WarData(	playPackageEverything :Seq[java.io.File],
							dependencyClasspath :Seq[sbt.Attributed[java.io.File]],
							packageFile :java.io.File,
							explodedJar :Boolean,
							servletVersion :String,
							webappResource :java.io.File,
							disableWarningWhenWebxmlFileFound :Boolean,
							targetName :Option[String],
							defaultFilteredArtifacts :Seq[(String, String)],
							filteredArtifacts :Seq[(String, String)]
    )