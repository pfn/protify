import java.io.BufferedReader

import bintray.Keys._

import scala.annotation.tailrec

// val desktop = project.in(file("desktop"))

val common = project.in(file("common")).settings(
  crossPaths := false,
  autoScalaLibrary := false,
  organization := "com.hanhuy.android",
  name := "protify-common",
  javacOptions in Compile ++= "-target" :: "1.7" :: "-source" :: "1.7" :: Nil,
  javacOptions in (Compile,doc) := {
    (javacOptions in doc).value flatMap { opt =>
      if (opt.startsWith("-Xbootclasspath/a"))
        Seq("-bootclasspath", opt.substring(opt.indexOf(":") + 1))
      else if (opt == "-g")
        Seq.empty
      else Seq(opt)
    }
  },
  exportJars := true
)

val plugin = project.in(file("sbt-plugin")).settings(
  bintrayPublishSettings ++ scriptedSettings ++
    addSbtPlugin("com.hanhuy.sbt" % "android-sdk-plugin" % "1.4.9-SNAPSHOT" % "provided")
).settings(
  name := "android-protify",
  organization := "com.hanhuy.sbt",
  scalacOptions ++= Seq("-deprecation","-Xlint","-feature"),
  sbtPlugin := true,
  repository in bintray := "sbt-plugins",
  publishMavenStyle := false,
  licenses += ("MIT", url("http://opensource.org/licenses/MIT")),
  scriptedLaunchOpts ++= Seq("-Xmx1024m",
    "-Dplugin.version=" + version.value
  ),
  bintrayOrganization in bintray := None,
  mappings in (Compile, packageBin) ++= (mappings in (Compile, packageBin) in common).value
).dependsOn(common)

val lib = project.in(file("lib")).settings(androidBuildJar).settings(
  platformTarget in Android := "android-15",
  lintFlags in Android := {
    val flags = (lintFlags in Android).value
    val layout = (projectLayout in Android).value
    val config = layout.bin / "library-lint.xml"
    (layout.manifest relativeTo layout.base) foreach { path =>
      val lintconfig = <lint>
        <issue id="ParserError">
          <ignore path={path.getPath} />
        </issue>
      </lint>
      scala.xml.XML.save(config.getAbsolutePath, lintconfig, "utf-8")
      flags.setDefaultConfiguration(config)
    }
    flags
  },
  autoScalaLibrary := false,
  organization := "com.hanhuy.android",
  name := "protify",
  publishMavenStyle := true,
  javacOptions in Compile ++= "-target" :: "1.7" :: "-source" :: "1.7" :: Nil,
  javacOptions in (Compile,doc) := {
    (javacOptions in doc).value flatMap { opt =>
      if (opt.startsWith("-Xbootclasspath/a"))
        Seq("-bootclasspath", opt.substring(opt.indexOf(":") + 1))
      else if (opt == "-g")
        Seq.empty
      else Seq(opt)
    }
  },
  publishTo := {
    val nexus = "https://oss.sonatype.org/"
    if (isSnapshot.value)
      Some("snapshots" at nexus + "content/repositories/snapshots")
    else
      Some("releases"  at nexus + "service/local/staging/deploy/maven2")
  },
  pomIncludeRepository := { _ => false },
  pomExtra :=
    <scm>
      <url>git@github.com:pfn/protify.git</url>
      <connection>scm:git:git@github.com:pfn/protify.git</connection>
    </scm>
    <developers>
      <developer>
        <id>pfnguyen</id>
        <name>Perry Nguyen</name>
        <url>https://github.com/pfn</url>
      </developer>
    </developers>,
  licenses := Seq("BSD-style" -> url("http://www.opensource.org/licenses/bsd-license.php")),
  homepage := Some(url("https://github.com/pfn/protify"))
)

val mobile = project.in(file("android")).settings(androidBuild).settings(
  platformTarget in Android := "android-22",
  minSdkVersion in Android := "16",
  targetSdkVersion in Android := "22",
  debugIncludesTests in Android := false,
  useProguard in Android := true,
  useProguardInDebug in Android := true,
  scalaVersion := "2.11.7",
  javacOptions in Compile ++= "-target" :: "1.7" :: "-source" :: "1.7" :: Nil,
  proguardScala in Android := true,
  rGenerator in Android := {
    val r = (rGenerator in Android).value

    def exists(in: BufferedReader)(f: String => Boolean): Boolean = {
      @tailrec
      def readLine(accum: Boolean): Boolean = {
        val line = in.readLine()
        if (accum || (line eq null)) accum else readLine(f(line))
      }
      readLine(false)
    }

    val supportR = r filter {
      Using.fileReader(IO.utf8)(_) { in =>
        exists(in)(_ contains "package android.support")
      }
    }
    supportR foreach { s =>
      val lines = IO.readLines(s)
      IO.writeLines(s, lines map (_.replaceAll(" final ", " ")))
    }
    r
  },
  proguardOptions in Android ++=
    "-keep class scala.runtime.BoxesRunTime { *; }" :: // for debugging only
    "-keep class android.support.** { *; }" ::
    "-keep class com.hanhuy.android.protify.ActivityProxy {*;}" ::
    "-keep class com.hanhuy.android.protify.ActivityProxy$Simple {*;}" ::
    Nil,
  libraryDependencies ++=
    "com.hanhuy.android" %% "scala-common" % "1.0" ::
    "com.android.support" % "appcompat-v7" % "22.2.1" ::
    "com.android.support" % "design" % "22.2.1" ::
    Nil,
  manifestPlaceholders in Android := Map(
    "vmSafeMode" -> (apkbuildDebug in Android).value().toString
  )
).dependsOn(lib, common)

val test1 = android.Plugin.flavorOf(mobile, "test1").settings(
  applicationId in Android := "com.hanhuy.android.protify.tests",
  debugIncludesTests in Android := true,
  instrumentTestRunner in Android :=
    "android.support.test.runner.AndroidJUnitRunner",
  libraryDependencies ++=
    "com.android.support.test" % "runner" % "0.3" ::
    "com.android.support.test.espresso" % "espresso-core" % "2.2" ::
    Nil,
  apkbuildExcludes in Android += "LICENSE.txt",
  proguardOptions in Android ++=
    "-dontwarn junit.**" ::
    "-dontwarn java.beans.**" ::
    "-dontwarn java.lang.management.**" ::
    "-dontwarn javax.lang.model.element.**" ::
    "-dontwarn org.jmock.**" ::
    "-dontwarn org.easymock.**" ::
    "-dontwarn com.google.appengine.**" ::
    "-keepclasseswithmembers class * { @org.junit.Test <methods>; }" ::
    "-keepclassmembers class scala.reflect.ScalaSignature { java.lang.String bytes(); }" ::
    "-keep class android.support.test.** { *; }" ::
    Nil
)

test <<= test in (test1,Android)

Keys.`package` in Android <<= Keys.`package` in (mobile,Android)

version in Global := "0.1-SNAPSHOT"
