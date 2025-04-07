
ThisBuild / version := "0.1.0-SNAPSHOT"
val circeVersion = "0.14.6"

//ThisBuild / scalaVersion := "2.13.16"
ThisBuild / scalaVersion := "2.13.12"

lazy val root = (project in file("."))
  .settings(
    name := "Scala programming language"
  )
libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % "2.8.5",
  "com.typesafe.akka" %% "akka-stream" % "2.8.5",
  "com.typesafe.akka" %% "akka-http" % "10.5.3",
  "com.typesafe.akka" %% "akka-http-spray-json" % "10.5.3",
  "com.lihaoyi" %% "requests" % "0.8.0",    // для HTTP-запросов
  "io.circe" %% "circe-parser" % "0.14.6",  // для работы с JSON
  "org.scala-lang.modules" %% "scala-xml" % "2.2.0",
  "org.json4s" %% "json4s-jackson" % "4.0.6",
  "org.scala-lang.modules" %% "scala-xml" % "2.1.0",
  "org.json4s" %% "json4s-xml" % "4.0.6",
  "com.softwaremill.sttp.client3" %% "core" % "3.9.1",
  "javax.xml.bind" % "jaxb-api" % "2.3.1",
  "org.glassfish.jaxb" % "jaxb-runtime" % "2.3.3",
  "io.circe" %% "circe-core" % circeVersion,
  "io.circe" %% "circe-generic" % circeVersion,
  "io.circe" %% "circe-parser" % circeVersion,
  "com.typesafe" % "config" % "1.4.2"
)
libraryDependencies += "com.fasterxml.woodstox" % "woodstox-core" % "6.5.1"
libraryDependencies += "org.codehaus.woodstox" % "stax2-api" % "4.2.2"

assembly / assemblyMergeStrategy := {
  case PathList("META-INF", xs @ _*) => MergeStrategy.discard
  case x => MergeStrategy.first
}