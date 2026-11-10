name := "todo-mcp"
organization := "com.example"
version := "1.0.0"

scalaVersion := "2.13.18"

lazy val root = (project in file("."))
  .enablePlugins(PlayJava)
  .settings(
    libraryDependencies ++= Seq(
      guice,
      javaWs
    ),
    javacOptions ++= Seq("--release", "21", "-Xlint:unchecked", "-Xlint:deprecation"),
    // No docs/sources in the staged app
    Compile / doc / sources := Seq.empty,
    Compile / packageDoc / publishArtifact := false
  )
