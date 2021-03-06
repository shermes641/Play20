package sbt

import Keys._
import jline._

import play.api._
import play.core._

object PlayProject extends Plugin {

  // ----- We need this later

  private val consoleReader = new jline.ConsoleReader

  private def waitForKey() = {
    consoleReader.getTerminal.disableEcho()
    def waitEOF {
      consoleReader.readVirtualKey() match {
        case 4 => // STOP
        case 11 => consoleReader.clearScreen(); waitEOF
        case 10 => println(); waitEOF
        case _ => waitEOF
      }

    }
    waitEOF
    consoleReader.getTerminal.enableEcho()
  }

  // ----- Exceptions

  case class CompilationException(problem: xsbti.Problem) extends PlayException(
    "Compilation error", problem.message) with PlayException.ExceptionSource {
    def line = problem.position.line.map(m => m.asInstanceOf[Int])
    def position = problem.position.pointer.map(m => m.asInstanceOf[Int])
    def input = problem.position.sourceFile.map(scalax.file.Path(_))
    def sourceName = problem.position.sourceFile.map(_.getAbsolutePath)
  }

  case class TemplateCompilationException(source: File, message: String, atLine: Int, column: Int) extends PlayException(
    "Compilation error", message) with PlayException.ExceptionSource {
    def line = Some(atLine)
    def position = Some(column)
    def input = Some(scalax.file.Path(source))
    def sourceName = Some(source.getAbsolutePath)
  }

  case class RoutesCompilationException(source: File, message: String, atLine: Option[Int], column: Option[Int]) extends PlayException(
    "Compilation error", message) with PlayException.ExceptionSource {
    def line = atLine
    def position = column
    def input = Some(scalax.file.Path(source))
    def sourceName = Some(source.getAbsolutePath)
  }

  // ----- Keys

  val distDirectory = SettingKey[File]("play-dist")
  val playResourceDirectories = SettingKey[Seq[File]]("play-resource-directories")
  val confDirectory = SettingKey[File]("play-conf")
  val templatesImport = SettingKey[Seq[String]]("play-templates-imports")
  val templatesTypes = SettingKey[(String => (String, String))]("play-templates-formats")

  // -- Utility methods for 0.10-> 0.11 migration
  def inAllDeps[T](base: ProjectRef, deps: ProjectRef => Seq[ProjectRef], key: ScopedSetting[T], data: Settings[Scope]): Seq[T] =
    inAllProjects(Dag.topologicalSort(base)(deps), key, data)
  def inAllProjects[T](allProjects: Seq[Reference], key: ScopedSetting[T], data: Settings[Scope]): Seq[T] =
    allProjects.flatMap { p => key in p get data }

  def inAllDependencies[T](base: ProjectRef, key: ScopedSetting[T], structure: Load.BuildStructure): Seq[T] = {
    def deps(ref: ProjectRef): Seq[ProjectRef] =
      Project.getProject(ref, structure).toList.flatMap { p =>
        p.dependencies.map(_.project) ++ p.aggregate
      }
    inAllDeps(base, deps, key, structure.data)
  }

  // ----- Play specific tasks
  val playCompileEverything = TaskKey[Seq[sbt.inc.Analysis]]("play-compile-everything")
  val playCompileEverythingTask = (state, thisProjectRef) flatMap { (s, r) =>
    inAllDependencies(r, (compile in Compile).task, Project structure s).join
  }

  val playPackageEverything = TaskKey[Seq[File]]("play-package-everything")
  val playPackageEverythingTask = (state, thisProjectRef) flatMap { (s, r) =>
    inAllDependencies(r, (packageBin in Compile).task, Project structure s).join
  }

  val playCopyResources = TaskKey[Seq[(File, File)]]("play-copy-resources")
  val playCopyResourcesTask = (baseDirectory, managedResources in Compile, resourceManaged in Compile, playResourceDirectories, classDirectory in Compile, cacheDirectory, streams) map { (b, resources, resourcesDirectories, r, t, c, s) =>
    val cacheFile = c / "copy-resources"
    val mappings = (r.map(_ ***).reduceLeft(_ +++ _) x rebase(b, t)) ++ (resources x rebase(resourcesDirectories, t))
    s.log.debug("Copy play resource mappings: " + mappings.mkString("\n\t", "\n\t", ""))
    Sync(cacheFile)(mappings)
    mappings
  }

  val playReload = TaskKey[sbt.inc.Analysis]("play-reload")
  val playReloadTask = (playCopyResources, playCompileEverything) map { (_, analysises) =>
    analysises.reduceLeft(_ ++ _)
  }

  val dist = TaskKey[File]("dist", "Build the standalone application package")
  val distTask = (baseDirectory, playPackageEverything, dependencyClasspath in Runtime, target, normalizedName, version) map { (root, packaged, dependencies, target, id, version) =>

    import sbt.NameFilter._

    val dist = root / "dist"
    val packageName = id + "-" + version
    val zip = dist / (packageName + ".zip")

    IO.delete(dist)
    IO.createDirectory(dist)

    val libs = {
      dependencies.filter(_.data.ext == "jar").map { dependency =>
        dependency.data -> (packageName + "/lib/" + (dependency.metadata.get(AttributeKey[ModuleID]("module")).map { module =>
          module.organization + "." + module.name + "-" + module.revision + ".jar"
        }.getOrElse(dependency.data.getName)))
      } ++ packaged.map(jar => jar -> (packageName + "/lib/" + jar.getName))
    }

    val run = target / "run"
    IO.write(run,
      """java "$@" -cp "`dirname $0`/lib/*" play.core.server.NettyServer `dirname $0`""" /**/ )
    val scripts = Seq(run -> (packageName + "/run"))

    val conf = Seq((root / "conf" / "application.conf") -> (packageName + "/conf/application.conf"))

    IO.zip(libs ++ scripts ++ conf, zip)
    IO.delete(run)

    println()
    println("Your application is ready in " + zip.getCanonicalPath)
    println()

    zip
  }

  // ----- Assets

  val LessCompiler = (sourceDirectory in Compile, resourceManaged in Compile, cacheDirectory) map { (src, resources, cache) =>

    import java.io._

    val cacheFile = cache / "less"
    val lessFiles = (src / "assets") ** "*.less"
    val currentInfos = lessFiles.get.map(f => f -> FileInfo.lastModified(f)).toMap
    val (previousRelation, previousInfo) = Sync.readInfo(cacheFile)(FileInfo.lastModified.format)

    if (previousInfo != currentInfos) {

      // Delete previous generated CSS files
      previousRelation._2s.foreach(IO.delete)

      val generated = ((lessFiles --- ((src / "assets") ** "_*")) x relativeTo(Seq(src / "assets"))).map {
        case (lessFile, name) => lessFile -> ("public/" + name.replace(".less", ".css"))
      }.flatMap {
        case (lessFile, name) => {
          val ((css, dependencies), out) = play.core.less.LessCompiler.compile(lessFile) -> new File(resources, name)
          IO.write(out, css)
          dependencies.map(_ -> out)
        }
      }

      Sync.writeInfo(cacheFile,
        Relation.empty[File, File] ++ generated,
        currentInfos)(FileInfo.lastModified.format)

      // Return new CSS
      generated.toMap.values.toSeq

    } else {

      // Return previously generated CSS
      previousRelation._2s.toSeq

    }

  }

  val CoffeescriptCompiler = (sourceDirectory in Compile, resourceManaged in Compile, cacheDirectory) map { (src, resources, cache) =>

    import java.io._

    val cacheFile = cache / "coffeescript"
    val currentRelation = Relation.empty ++ ((src / "assets") ** "*.coffee" x relativeTo(Seq(src / "assets"))).map {
      case (csFile, name) => csFile -> new File(resources, "public/" + name.replace(".coffee", ".js"))
    }
    val currentInfos = currentRelation._1s.map(f => f -> FileInfo.lastModified(f)).toMap
    val (previousRelation, previousInfo) = Sync.readInfo(cacheFile)(FileInfo.lastModified.format)

    val removeTargets = previousRelation._2s -- currentRelation._2s
    val update = currentRelation filter { (source, target) =>
      if (target.exists) {
        target.lastModified < source.lastModified
      } else {
        true
      }
    }

    removeTargets.foreach(IO.delete)
    update.all.foreach {
      case (source, target) => {
        val compiled = play.core.coffeescript.CoffeescriptCompiler.compile(source)
        IO.write(target, compiled)
      }
    }

    Sync.writeInfo(cacheFile,
      currentRelation,
      currentInfos)(FileInfo.lastModified.format)

    currentRelation._2s.toSeq
  }

  // ----- Post compile (need to be refactored and fully configurable)

  val PostCompile = (sourceDirectory in Compile, dependencyClasspath in Compile, compile in Compile, javaSource in Compile, sourceManaged in Compile, classDirectory in Compile) map { (src, deps, analysis, javaSrc, srcManaged, classes) =>

    // Properties

    val classpath = (deps.map(_.data.getAbsolutePath).toArray :+ classes.getAbsolutePath).mkString(":")

    val javaClasses = (javaSrc ** "*.java").get.map { sourceFile =>
      analysis.relations.products(sourceFile)
    }.flatten.distinct

    javaClasses.foreach(play.core.enhancers.PropertiesEnhancer.generateAccessors(classpath, _))
    javaClasses.foreach(play.core.enhancers.PropertiesEnhancer.rewriteAccess(classpath, _))

    // EBean

    try {

      val cp = deps.map(_.data.toURL).toArray :+ classes.toURL

      import com.avaje.ebean.enhance.agent._
      import com.avaje.ebean.enhance.ant._

      val cl = ClassLoader.getSystemClassLoader

      val t = new Transformer(cp, "debug=-1")

      val ft = new OfflineFileTransform(t, cl, classes.getAbsolutePath, classes.getAbsolutePath)
      ft.process("models/**")

    } catch {
      case _ =>
    }

    // Copy managed classes

    val managedClassesDirectory = classes.getParentFile / (classes.getName + "_managed")

    val managedClasses = (srcManaged ** "*.scala").get.map { managedSourceFile =>
      analysis.relations.products(managedSourceFile)
    }.flatten x rebase(classes, managedClassesDirectory)

    // Copy modified class files
    val managedSet = IO.copy(managedClasses)

    // Remove deleted class files
    (managedClassesDirectory ** "*.class").get.filterNot(managedSet.contains(_)).foreach(_.delete())

    analysis
  }

  // ----- Source generators

  val RouteFiles = (confDirectory: File, generatedDir: File) => {
    import play.core.Router.RoutesCompiler._

    ((generatedDir ** "routes.java").get ++ (generatedDir ** "routes_*.scala").get).map(GeneratedSource(_)).foreach(_.sync())
    try {
      (confDirectory * "routes").get.foreach { routesFile =>
        compile(routesFile, generatedDir)
      }
    } catch {
      case RoutesCompilationError(source, message, line, column) => {
        throw RoutesCompilationException(source, message, line, column.map(_ - 1))
      }
      case e => throw e
    }

    ((generatedDir ** "routes_*.scala").get ++ (generatedDir ** "routes.java").get).map(_.getAbsoluteFile)

  }

  val ScalaTemplates = (sourceDirectory: File, generatedDir: File, templateTypes: Function1[String, (String, String)], additionalImports: Seq[String]) => {
    import play.templates._

    (generatedDir ** "*.template.scala").get.map(GeneratedSource(_)).foreach(_.sync())
    try {
      (sourceDirectory ** "*.scala.html").get.foreach { template =>
        ScalaTemplateCompiler.compile(
          template,
          sourceDirectory,
          generatedDir,
          templateTypes("html")._1,
          templateTypes("html")._2,
          additionalImports.map("import " + _).mkString("\n"))
      }
    } catch {
      case TemplateCompilationError(source, message, line, column) => {
        throw TemplateCompilationException(source, message, line, column - 1)
      }
      case e => throw e
    }

    (generatedDir ** "*.template.scala").get.map(_.getAbsoluteFile)
  }

  // ----- Play prompt

  val playPrompt = { state: State =>

    val extracted = Project.extract(state)
    import extracted._

    (name in currentRef get structure.data).map { name =>
      new ANSIBuffer().append("[").cyan(name).append("] $ ").toString
    }.getOrElse("> ")

  }

  // ----- Reloader

  def newReloader(state: State) = {

    val extracted = Project.extract(state)

    new ReloadableApplication(extracted.currentProject.base) {

      // ----- Internal state used for reloading is kept here

      val watchFiles = {
        ((extracted.currentProject.base / "db" / "evolutions") ** "*.sql").get ++ ((extracted.currentProject.base / "conf") ** "*").get
      }

      var forceReload = false
      var currentProducts = Map.empty[java.io.File, Long]
      var currentAnalysis = Option.empty[sbt.inc.Analysis]

      def forceReloadNextTime() { forceReload = true }

      def updateAnalysis(newAnalysis: sbt.inc.Analysis) = {
        val classFiles = newAnalysis.stamps.allProducts ++ watchFiles
        val newProducts = classFiles.map { classFile =>
          classFile -> classFile.lastModified
        }.toMap
        val updated = if (newProducts != currentProducts || forceReload) {
          Some(newProducts)
        } else {
          None
        }
        updated.foreach(currentProducts = _)
        currentAnalysis = Some(newAnalysis)

        forceReload = false

        updated
      }

      def findSource(className: String) = {
        val topType = className.split('$').head
        currentAnalysis.flatMap { analysis =>
          analysis.apis.internal.flatMap {
            case (sourceFile, source) => {
              source.api.definitions.find(defined => defined.name == topType).map(_ => {
                sourceFile: java.io.File
              })
            }
          }.headOption
        }
      }

      def remapProblemForGeneratedSources(problem: xsbti.Problem) = {

        problem.position.sourceFile.collect {

          // Templates
          case play.templates.MaybeGeneratedSource(generatedSource) => {
            new xsbti.Problem {
              def message = problem.message
              def position = new xsbti.Position {
                def line = {
                  problem.position.line.map(l => generatedSource.mapLine(l.asInstanceOf[Int])).map(l => xsbti.Maybe.just(l.asInstanceOf[java.lang.Integer])).getOrElse(xsbti.Maybe.nothing[java.lang.Integer])
                }
                def lineContent = ""
                def offset = xsbti.Maybe.nothing[java.lang.Integer]
                def pointer = {
                  problem.position.offset.map { offset =>
                    generatedSource.mapPosition(offset.asInstanceOf[Int]) - IO.readLines(generatedSource.source.get).take(problem.position.line.map(l => generatedSource.mapLine(l.asInstanceOf[Int])).get - 1).mkString("\n").size - 1
                  }.map { p =>
                    xsbti.Maybe.just(p.asInstanceOf[java.lang.Integer])
                  }.getOrElse(xsbti.Maybe.nothing[java.lang.Integer])
                }
                def pointerSpace = xsbti.Maybe.nothing[String]
                def sourceFile = xsbti.Maybe.just(generatedSource.source.get)
                def sourcePath = xsbti.Maybe.just(sourceFile.get.getCanonicalPath)
              }
              def severity = problem.severity
            }
          }

          // Routes files
          case play.core.Router.RoutesCompiler.MaybeGeneratedSource(generatedSource) => {
            new xsbti.Problem {
              def message = problem.message
              def position = new xsbti.Position {
                def line = {
                  problem.position.line.flatMap(l => generatedSource.mapLine(l.asInstanceOf[Int])).map(l => xsbti.Maybe.just(l.asInstanceOf[java.lang.Integer])).getOrElse(xsbti.Maybe.nothing[java.lang.Integer])
                }
                def lineContent = ""
                def offset = xsbti.Maybe.nothing[java.lang.Integer]
                def pointer = xsbti.Maybe.nothing[java.lang.Integer]
                def pointerSpace = xsbti.Maybe.nothing[String]
                def sourceFile = xsbti.Maybe.just(new File(generatedSource.source.get.path))
                def sourcePath = xsbti.Maybe.just(sourceFile.get.getCanonicalPath)
              }
              def severity = problem.severity
            }
          }

        }.getOrElse {
          problem
        }

      }

      def getProblems(incomplete: Incomplete): Seq[xsbti.Problem] = {
        (Compiler.allProblems(incomplete) ++ {
          Incomplete.linearize(incomplete).filter(i => i.node.isDefined && i.node.get.isInstanceOf[ScopedKey[_]]).flatMap { i =>
            val JavacError = """\[error\]\s*(.*[.]java):(\d+):\s*(.*)""".r
            val JavacErrorInfo = """\[error\]\s*([a-z ]+):(.*)""".r
            val JavacErrorPosition = """\[error\](\s*)\^\s*""".r

            Project.evaluateTask(streamsManager, state).get.toEither.right.toOption.map { streamsManager =>
              var first: (Option[(String, String, String)], Option[Int]) = (None, None)
              var parsed: (Option[(String, String, String)], Option[Int]) = (None, None)
              Output.lastLines(i.node.get.asInstanceOf[ScopedKey[_]], streamsManager).map(_.replace(scala.Console.RESET, "")).map(_.replace(scala.Console.RED, "")).collect {
                case JavacError(file, line, message) => parsed = Some((file, line, message)) -> None
                case JavacErrorInfo(key, message) => parsed._1.foreach { o =>
                  parsed = Some((parsed._1.get._1, parsed._1.get._2, parsed._1.get._3 + " [" + key.trim + ": " + message.trim + "]")) -> None
                }
                case JavacErrorPosition(pos) => {
                  parsed = parsed._1 -> Some(pos.size)
                  if (first == (None, None)) {
                    first = parsed
                  }
                }
              }
              first
            }.collect {
              case (Some(error), maybePosition) => new xsbti.Problem {
                def message = error._3
                def position = new xsbti.Position {
                  def line = xsbti.Maybe.just(error._2.toInt)
                  def lineContent = ""
                  def offset = xsbti.Maybe.nothing[java.lang.Integer]
                  def pointer = maybePosition.map(pos => xsbti.Maybe.just((pos - 1).asInstanceOf[java.lang.Integer])).getOrElse(xsbti.Maybe.nothing[java.lang.Integer])
                  def pointerSpace = xsbti.Maybe.nothing[String]
                  def sourceFile = xsbti.Maybe.just(file(error._1))
                  def sourcePath = xsbti.Maybe.just(error._1)
                }
                def severity = xsbti.Severity.Error
              }
            }

          }
        }).map(remapProblemForGeneratedSources)
      }

      private def newClassloader = {
        new ApplicationClassLoader(this.getClass.getClassLoader, {
          Project.evaluateTask(dependencyClasspath in Runtime, state).get.toEither.right.get.map(_.data.toURI.toURL).toArray
        })
      }

      def reload = {

        PlayProject.synchronized {

          Project.evaluateTask(playReload, state).get.toEither
            .left.map { incomplete =>
              Incomplete.allExceptions(incomplete).headOption.map {
                case e: PlayException => e
                case e: xsbti.CompileFailed => {
                  getProblems(incomplete).headOption.map(CompilationException(_)).getOrElse {
                    UnexpectedException(Some("Compilation failed without reporting any problem!?"), Some(e))
                  }
                }
                case e => UnexpectedException(unexpected = Some(e))
              }.getOrElse(
                UnexpectedException(Some("Compilation task failed without any exception!?")))
            }
            .right.map { compilationResult =>
              updateAnalysis(compilationResult).map { _ =>
                newClassloader
              }
            }

        }

      }

      override def handleWebCommand(request: play.api.mvc.RequestHeader) = {

        val applyEvolutions = """/@evolutions/apply/([a-zA-Z0-9_]+)""".r

        request.path match {

          case applyEvolutions(db) => {
            import play.api.db._
            import play.api.db.evolutions._
            import play.api.mvc.Results._

            OfflineEvolutions.applyScript(extracted.currentProject.base, newClassloader, db)

            forceReloadNextTime()

            Some(Redirect(request.queryString.get("redirect").filterNot(_.isEmpty).map(_(0)).getOrElse("/")))
          }

          case _ => None

        }
      }

    }

  }

  // ----- Play commands

  val playRunCommand = Command.command("run") { state: State =>

    val reloader = newReloader(state)

    println()

    val server = new play.core.server.NettyServer(reloader)

    println()
    println(new ANSIBuffer().green("(Server started, use Ctrl+D to stop and go back to the console...)").toString)
    println()

    waitForKey()

    server.stop()

    println()

    state
  }

  val playStartCommand = Command.command("start") { state: State =>

    val extracted = Project.extract(state)

    Project.evaluateTask(compile in Compile, state).get.toEither match {
      case Left(_) => {
        println()
        println("Cannot start with errors.")
        println()
        state.fail
      }
      case Right(_) => {

        Project.evaluateTask(dependencyClasspath in Runtime, state).get.toEither.right.map { dependencies =>

          val classpath = dependencies.map(_.data).map(_.getCanonicalPath).reduceLeft(_ + java.io.File.pathSeparator + _)

          import java.lang.{ ProcessBuilder => JProcessBuilder }
          val builder = new JProcessBuilder(Array(
            "java", "-cp", classpath, "play.core.server.NettyServer", extracted.currentProject.base.getCanonicalPath): _*)

          new Thread {
            override def run {
              System.exit(Process(builder) !)
            }
          }.start()

          println(new ANSIBuffer().green(
            """|
                           |(Starting server. Type Ctrl+D to exit logs, the server will remain in background)
                           |""".stripMargin).toString)

          waitForKey()

          println()

          state.copy(remainingCommands = Seq.empty)

        }.right.getOrElse {
          println()
          println("Oops, cannot start the server?")
          println()
          state.fail
        }

      }
    }

  }

  val playHelpCommand = Command.command("help") { state: State =>

    println(
      """
                |Welcome to Play 2.0!
                |
                |These commands are available:
                |-----------------------------
                |classpath                  Display the project classpath.
                |clean                      Clean all generated files.
                |compile                    Compile the current application.
                |console                    Launch the interactive Scala console (use :quit to exit).
                |dist                       Construct standalone application package.
                |exit                       Exit the console.
                |h2-browser                 Launch the H2 Web browser.
                |license                    Display licensing informations.
                |package                    Package your application as a JAR.
                |publish                    Publish your application in a remote repository.
                |publish-local              Publish your application in the local repository.
                |reload                     Reload the current application build file.
                |run                        Run the current application in DEV mode.
                |start                      Start the current application in another JVM in PROD mode.
                |update                     Update application dependencies.
                |
                |You can also use any sbt feature:
                |---------------------------------
                |about                      Displays basic information about sbt and the build.
                |reboot [full]              Reboots sbt and then executes the remaining commands.
                |< file*                    Reads command lines from the provided files.
                |!!                         Execute the last command again
                |!:                         Show all previous commands
                |!:n                        Show the last n commands
                |!n                         Execute the command with index n, as shown by the !: command
                |!-n                        Execute the nth command before this one
                |!string                    Execute the most recent command starting with 'string'
                |!?string                   Execute the most recent command containing 'string'
                |~ <action>                 Executes the specified command whenever source files change.
                |projects                   Displays the names of available projects.
                |project [project]          Displays the current project or changes to the provided `project`.
                |- command                  Registers 'command' to run if a command fails.
                |iflast command             If there are no more commands after this one, 'command' is run.
                |( ; command )+             Runs the provided semicolon-separated commands.
                |set <setting-expression>   Evaluates the given Setting and applies to the current project.
                |tasks                      Displays the tasks defined for the current project.
                |inspect <key>              Prints the value for 'key', the defining scope, delegates, related definitions, and dependencies.
                |eval <expression>          Evaluates the given Scala expression and prints the result and type.
                |alias                      Adds, removes, or prints command aliases.
                |append command             Appends `command` to list of commands to run.
                |last <key>                 Prints the last output associated with 'key'.
                |last-grep <pattern> <key>  Shows lines from the last output for 'key' that match 'pattern'.
                |session ...                Manipulates session settings.  For details, run 'help session'..
                |
                |Browse the complete documentation at """.stripMargin +
        new ANSIBuffer().underscore("http://www.playframework.org").append(".\n"))

    state
  }

  val playCommand = Command.command("play") { state: State =>

    val extracted = Project.extract(state)
    import extracted._

    // Display logo
    println(play.console.Console.logo)
    println("""
            |> Type "help" or "license" for more information.
            |> Type "exit" or use Ctrl+D to leave this console.
            |""".stripMargin)

    state.copy(
      remainingCommands = state.remainingCommands :+ "shell")

  }

  val h2Command = Command.command("h2-browser") { state: State =>
    try {
      org.h2.tools.Server.main()
    } catch {
      case _ =>
    }
    state
  }

  val licenseCommand = Command.command("license") { state: State =>
    println(
      """
      |This software is licensed under the Apache 2 license, quoted below.
      |
      |Copyright 2011 Zenexity <http://www.zenexity.com>
      |
      |Licensed under the Apache License, Version 2.0 (the "License"); you may not
      |use this file except in compliance with the License. You may obtain a copy of
      |the License at
      |
      |    http://www.apache.org/licenses/LICENSE-2.0
      |
      |Unless required by applicable law or agreed to in writing, software
      |distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
      |WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
      |License for the specific language governing permissions and limitations under
      |the License.
      """.stripMargin)
    state
  }

  val classpathCommand = Command.command("classpath") { state: State =>

    val extracted = Project.extract(state)

    Project.evaluateTask(dependencyClasspath in Runtime, state).get.toEither match {
      case Left(_) => {
        println()
        println("Cannot compute the classpath")
        println()
        state.fail
      }
      case Right(classpath) => {
        println()
        println("Here is the computed classpath of your application:")
        println()
        classpath.foreach { item =>
          println("\t- " + item.data.getAbsolutePath)
        }
        println()
        state
      }
    }

  }

  // ----- Default settings

  lazy val defaultSettings = Seq[Setting[_]](

    target <<= baseDirectory / "target",

    sourceDirectory in Compile <<= baseDirectory / "app",

    confDirectory <<= baseDirectory / "conf",

    scalaSource in Compile <<= baseDirectory / "app",

    javaSource in Compile <<= baseDirectory / "app",

    distDirectory <<= baseDirectory / "dist",

    libraryDependencies += "play" %% "play" % play.core.PlayVersion.current,

    sourceGenerators in Compile <+= (confDirectory, sourceManaged in Compile) map RouteFiles,

    sourceGenerators in Compile <+= (sourceDirectory in Compile, sourceManaged in Compile, templatesTypes, templatesImport) map ScalaTemplates,

    commands ++= Seq(playCommand, playRunCommand, playStartCommand, playHelpCommand, h2Command, classpathCommand, licenseCommand),

    shellPrompt := playPrompt,

    copyResources in Compile <<= (copyResources in Compile, playCopyResources) map { (r, pr) => r ++ pr },

    mainClass in (Compile, run) := Some(classOf[play.core.server.NettyServer].getName),

    compile in (Compile) <<= PostCompile,

    dist <<= distTask,

    playCopyResources <<= playCopyResourcesTask,

    playCompileEverything <<= playCompileEverythingTask,

    playPackageEverything <<= playPackageEverythingTask,

    playReload <<= playReloadTask,

    cleanFiles <+= distDirectory.identity,

    resourceGenerators in Compile <+= LessCompiler,

    resourceGenerators in Compile <+= CoffeescriptCompiler,

    playResourceDirectories := Seq.empty[File],

    playResourceDirectories <+= baseDirectory / "conf",

    playResourceDirectories <+= baseDirectory / "public",

    templatesImport := Seq("play.api.templates._", "play.api.templates.PlayMagic._", "controllers._"),

    templatesTypes := ((extension) => extension match {
      case "html" => ("play.api.templates.Html", "play.api.templates.HtmlFormat")
    }))

  // ----- Create a Play project with default settings

  def apply(name: String, applicationVersion: String = "0.1", dependencies: Seq[ModuleID] = Nil, path: File = file(".")) = {

    Project(name, path)
      .settings(PlayProject.defaultSettings: _*)
      .settings(

        version := applicationVersion,

        libraryDependencies ++= dependencies,

        resolvers ++= Option(System.getProperty("play.home")).map { home =>
          Resolver.file("play-repository", file(home) / "../repository")
        }.toSeq)

  }

}
