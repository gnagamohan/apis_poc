name := "auth-service"
organization := "com.example"
version := "1.0.0"

scalaVersion := "2.13.18"

lazy val root = (project in file("."))
  .enablePlugins(PlayJava)
  .settings(
    libraryDependencies ++= Seq(
      guice,
      javaJdbc,
      evolutions,
      "org.postgresql" % "postgresql" % "42.7.14",
      "com.auth0" % "java-jwt" % "4.6.2",
      "org.mindrot" % "jbcrypt" % "0.4",
      "org.eclipse.angus" % "angus-mail" % "2.0.5"
    ),
    // java-jwt pulls a newer Jackson; Play 3.0's jackson-module-scala requires 2.14.x
    dependencyOverrides ++= Seq(
      "com.fasterxml.jackson.core" % "jackson-databind" % "2.14.3",
      "com.fasterxml.jackson.core" % "jackson-core" % "2.14.3",
      "com.fasterxml.jackson.core" % "jackson-annotations" % "2.14.3"
    ),
    javacOptions ++= Seq("--release", "21", "-Xlint:unchecked", "-Xlint:deprecation"),
    Compile / doc / sources := Seq.empty,
    Compile / packageDoc / publishArtifact := false
  )
