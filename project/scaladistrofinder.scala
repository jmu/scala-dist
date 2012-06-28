import sbt._
import Keys._
import com.typesafe.packager.Keys._
import sbt.Keys._
import com.typesafe.packager.PackagerPlugin._
import collection.mutable.ArrayBuffer

trait ScalaDistroDeps {
  val scalaDistInstance: TaskKey[ScalaInstance]
  val scalaDistDir: SettingKey[File]
  val scalaDistVersion: SettingKey[String]
}

object ScalaDistroFinder {
  val jenkinsUrl = SettingKey[String]("typesafe-build-server-url")
  val scalaDistJenkinsUrl = SettingKey[String]("scala-dist-jenkins-url")
  val scalaDistZipFile = SettingKey[File]("scala-dist-zip-file")

  val scalaDistZipLocation = SettingKey[File]("scala-dist-zip-location")  
  val scalaDistDir = SettingKey[File]("scala-dist-dir", "Resolves the Scala distribution and opens it into the desired location.")

  val scalaDistChecked = AttributeKey[Boolean]("scala-dist-location-checked")
  val scalaDistVersion = SettingKey[String]("scala-dist-version")


  def scalaDistInstance: Setting[_] = 
    scalaInstance <<=  (scalaDistDir, appConfiguration) map { (dir, app) => 
      val jars = (dir / "lib" ** "*.jar").get
      val lib = jars find (_.getName == "scala-library.jar") getOrElse sys.error("Could not find scala library in distro.")
      val comp = jars find (_.getName == "scala-compiler.jar") getOrElse sys.error("Could not find scala library in distro.")
      val extraJars = jars filterNot { f => (f.getName == "scala-library.jar") || (f.getName == "scala-compiler.jar") }
      ScalaInstance(lib, comp, app.provider.scalaProvider.launcher, extraJars:_*)
    }

  def findDistroSettings: Seq[Setting[_]] = Seq(
    jenkinsUrl := "http://10.0.1.211/",
    scalaDistJenkinsUrl <<= jenkinsUrl apply (_ + "job/scala-release-main/ws/dists/latest/*zip*/latest.zip"),
    commands += distCheckCommand,
    onLoad in Global <<= (onLoad in Global) ?? idFun[State],
    onLoad in Global <<= (onLoad in Global) apply ( _ andThen ("scala-dist-check" :: _))    
  )

  def extractDistroSettings: Seq[Setting[_]] = Seq(
     // Pulling latest distro code. TODO - something useful....
    scalaDistZipLocation <<= baseDirectory apply (_ / "target" / "dist"),
    // TODO - Make ScalaDistDir an Option[File]....
    scalaDistDir <<= scalaDistZipLocation apply (dir => findScalaDistro(dir) getOrElse dir)
  )

  def useDistroSettings: Seq[Setting[_]] = Seq(
    scalaDistInstance,
    scalaDistVersion <<= (scalaDistDir, version) apply { (dir, v) =>
      Versioning.getScalaVersionOr(dir / "lib" / "scala-library.jar", v)
    }
  )

  def allSettings: Seq[Setting[_]] = findDistroSettings ++ extractDistroSettings ++ useDistroSettings



  def distCheckCommand = Command.command("scala-dist-check") { (state: State) =>
    val extracted = Project.extract(state)
    import extracted._ 
    val distDir = extracted get scalaDistZipLocation
    val marker = distDir / "dist.exploded"
    if(marker.exists) state
    else {
      // TODO - Don't run if already run.
      val extracted = Project.extract(state)
      import extracted._ 
      val targetdir = extracted get target
      val scalaDistZip = targetdir / "tmp" / "scala-dist.zip"
      val downloadUrl = extracted get scalaDistJenkinsUrl
      if(!scalaDistZip.exists) {
        System.err.println("")
        System.err.println("[error]: Could not find: " + scalaDistZip.getAbsolutePath)
        System.err.println("")
        System.err.println("\tYou can build a scala-dist.zip from a scala project, or this build")
        System.err.println("\twill attempt to download it from the latest jenkins build:")
        System.err.println("\t" + downloadUrl)
        System.err.println()
      }
      // We need ot extract the dist *now* so we have settings available to our build....
      val zip = findOrDownloadZipFile(extracted get scalaDistJenkinsUrl, targetdir)
      extractAndCleanScalaDistro(zip, distDir)
      Project.setProject(session, structure, state).put(scalaDistChecked, true)
    }
  }

  def findOrDownloadZipFile(uri: String, dir: File): File = {
    // TODO - Look in the directory for any zip file?
    val file = dir / "tmp" / "scala-dist.zip"
    // Only create if it doesn't exist.   Allow users not to rely on hudson to test the build.
    if (!file.exists) {
      IO.touch(file)
      val writer = new java.io.BufferedOutputStream(new java.io.FileOutputStream(file))
      import dispatch._
      try Http(url(uri) >>> writer)
      finally writer.close()
    }
    file
  }

  def cleanScalaDistro(dir: File): Unit =
    for {
     f <- (dir ** "*.bat").get
    } Process(Seq("unix2dos", f.getAbsolutePath), None).! match {
      case 0 => ()
      case n => sys.error("Could not unix2dos: " + f.getAbsolutePath + ".  Exit code: " + n)
    }

  // Attempts to find the correct scala distribution directory...
  def findScalaDistro(dir: File): Option[File] =  {
    def isScalaDistDir(f: File): Boolean =
      f.isDirectory && (f / "lib" / "scala-library.jar").exists
    IO listFiles dir  find isScalaDistDir
  }

  def extractAndCleanScalaDistro(zip: File, dir: File): File = {
    if(!dir.exists) dir.mkdirs()
    val marker = dir / "dist.exploded"
    if(!marker.exists) {
      // Unzip distro to local filesystem.
      IO.unzip(zip, dir)   
      // TODO - Fix cleaning so it works on windows
      if(!(System.getProperty("os.name").toLowerCase contains "windows")) {
        cleanScalaDistro(dir)
      }
      IO.touch(marker)
    }
    findScalaDistro(dir) getOrElse error("could not find scala distro in " + zip.getAbsolutePath)
  }
}
