package org.scalaide.core.compiler

import scala.tools.nsc.interactive.Global
import scala.reflect.internal.util.SourceFile
import org.eclipse.core.resources.IFile
import org.scalaide.core.completion.CompletionContext
import org.scalaide.core.completion.CompletionProposal
import scala.concurrent.duration._
import scala.tools.nsc.interactive.Response
import org.scalaide.logging.HasLogger
import scala.tools.nsc.util.FailedInterrupt
import scala.tools.nsc.interactive.FreshRunReq
import scala.tools.nsc.interactive.MissingResponse
import org.eclipse.jdt.core.IJavaElement
import org.eclipse.jdt.core.IJavaProject
import org.scalaide.core.IScalaPlugin
import org.scalaide.core.internal.compiler.InternalCompilerServices
import org.scalaide.core.IScalaProject
import scala.tools.nsc.doc.base.comment.Comment
import org.scalaide.core.internal.compiler.ScalaPresentationCompiler
import org.eclipse.jface.text.IRegion
import org.eclipse.jface.text.hyperlink.IHyperlink

/** This interface provides access to Scala Presentation compiler services. Even though methods are inherited from
 *  `scala.tools.nsc.interactive.Global`, prefer the convenience methods offered in this trait.
 *
 *  The presentation compiler is an asynchronous layer on top of the Scala type-checker. The PC
 *  works by *managing* a set of compilation units. A managed unit is called 'loaded', and
 *  all loaded units are parsed and type-checked together. A new compilation *run* is triggered
 *  by a *re-load*.
 *
 *  A unit can be in the following states:
 *    - unloaded (unmanaged). The PC won't attempt to parse or type-check it. Usually, any Scala file that is not
 *               open in an editor is not loaded
 *    - loaded. This state corresponds to units open inside an editor. In this state, every askReload (for example,
 *              triggered by a keystroke) will parse and type-check (and report errors as a side effect) such units. A
 *              unit gets in this state after a call to `askReload(unit, contents)`.
 *    - dirty. Units that have changes that haven't been reloaded yet. This is usually a subset of `loaded` (excluding
 *             files that have been deleted or closed). A unit is added to the dirty set using `scheduleReload`, and removed
 *             when `flushScheduledReloads` is called (usually after the reconciliation timeout, 500ms). Dirty units are
 *             flushed automatically when calling various `ask` methods, so that completion and hyperlinking are always
 *             up-to-date
 *    - crashed. A loaded unit that caused the type-checker to crash will be in this state. It won't be parsed
 *               nor type-checked anymore. To re-enable it, call `askToDoFirst`, which is usually called when an editor is
 *               open (meaning that when a file was closed and reopen it will be retried).
 *
 *  @note The self-type is necessary, since it changes the way calls to overridden ask methods are dispatched. Without the self-type
 *        they would go to the `CompilerControl` implementation, missing the overrides that call `flushScheduledReloads`
 */
trait IScalaPresentationCompiler extends Global with CompilerApiExtensions with InternalCompilerServices { self: ScalaPresentationCompiler =>
  import IScalaPresentationCompiler._

  /** Removes source files and top-level symbols, and issues a new typer run.
   *
   *  @return A `Response[Unit]` to sync when the operation was completed.
   */
  def askFilesDeleted(sources: List[SourceFile]): Response[Unit] =
    withResponse[Unit](askFilesDeleted(sources, _))

  /** Return the position of the definition of the given symbol in the given source file.
   *
   *  @param   sym      The symbol referenced by the link (might come from a classfile)
   *  @param   source   The source file that's supposed to contain the definition
   *  @return           A response that will be set to the following:
   *                    If `source` contains a definition that is referenced by the given link
   *                    the position of that definition, otherwise NoPosition.
   *
   *  @note This operation does not automatically load `source`. If `source`
   *  is unloaded, it stays that way.
   */
  def askLinkPos(sym: Symbol, source: SourceFile): Response[Position] =
    withResponse[Position](askLinkPos(sym, source, _))

  /** Return the parse tree of `source` with all top-level symbols entered.
   *
   *  @param source       The source file to be analyzed
   *  @param keepLoaded   If set to `true`, source file will be kept as a loaded unit afterwards.
   *                      If keepLoaded is `false` the operation is run at low priority, only after
   *                      everything is brought up to date in a regular type checker run.
   *  @return             A response set to the parsed and named tree for the given unit.
   */
  def askParsedEntered(source: SourceFile, keepLoaded: Boolean): Response[Tree] =
    withResponse[Tree](askParsedEntered(source, keepLoaded, _))

  /** Returns in `response` a list of members that are visible
   *  as members of the scope enclosing `pos`.
   *
   *  @pre  The source containing `pos` is loaded
   */
  def askScopeCompletion(pos: Position): Response[List[Member]] =
    withResponse[List[Member]](askScopeCompletion(pos, _))

  /** Returns in `response` a list of members that are visible
   *  as members of the tree enclosing `pos`, possibly reachable by an implicit.
   *
   *  @pre  The source containing `pos` is loaded
   */
  def askTypeCompletion(pos: Position): Response[List[Member]] =
    withResponse[List[Member]](askTypeCompletion(pos, _))

  /** Returns a `response` containing the smallest fully attributed tree that encloses position `pos`.
   *
   *  Sometimes the smallest enclosing tree is not what you expect, for example when a polymorphic
   *  method call is at `pos`. The returned tree is the tree below the type application, meaning that
   *  it has the generic type, instead of the applied type.
   *
   *  @note Unlike for most other ask... operations, the source file belonging to `pos` needs not be loaded.
   */
  def askTypeAt(pos: Position): Response[Tree] =
    withResponse[Tree](askTypeAt(pos, _))

  /** If source is not yet loaded, loads it, and starts a new run, otherwise
   *  continues with current pass.
   *  Waits until source is fully type checked and returns body in response.
   *
   *  @param sourceFile The source file that needs to be fully typed.
   *  @param keepLoaded Whether to keep that file in the PC if it was not loaded before. If
   *                    the file is already loaded, this flag is ignored.
   *  @return           The response, which is set to the fully attributed tree of `source`.
   *                    If the unit corresponding to `source` has been removed in the meantime
   *                    the a NoSuchUnitError is raised in the response.
   */
  def askLoadedTyped(sourceFile: SourceFile, keepLoaded: Boolean): Response[Tree] =
    withResponse[Tree](askLoadedTyped(sourceFile, keepLoaded, _))

  /** If source if not yet loaded, get an outline view with askParseEntered.
   *  If source is loaded, wait for it to be type-checked.
   *  In both cases, set response to parsed (and possibly type-checked) tree.
   *
   *  @param sourceFile The source file that needs to be fully typed.
   *  @param keepLoaded Whether to keep that file in the PC if it was not loaded before. If
   *                    the file is already loaded, this flag is ignored.
   */
  def askStructure(sourceFile: SourceFile, keepLoaded: Boolean = false): Response[Tree]

  /** Ask to put `scu` in the beginning of the list of files to be typechecked.
   *
   *  If the file has not been 'reloaded' first, it does nothing. If the file was marked as `crashed`,
   *  this method will add it back to the managed file set, and type-check it from now on.
   */
  def askToDoFirst(scu: InteractiveCompilationUnit): Unit

  /** Asks for a computation to be done quickly on the presentation compiler thread
   *
   *  This operation might interrupt background type-checking and take precedence. It
   *  is important that such operations are fast, or otherwise they will 'starve' any
   *  job waiting for a full type-check.
   */
  def asyncExec[A](op: => A): Response[A]

  /** Returns a `Response` containing doc comment information for a given symbol.
   *
   *  @param   sym        The symbol whose doc comment should be retrieved (might come from a classfile)
   *  @param   source     The source file that's supposed to contain the definition
   *  @param   site       The symbol where 'sym' is observed
   *  @param   fragments  All symbols that can contribute to the generated documentation
   *                      together with their source files.
   *  @return   response   A response that will be set to the following:
   *                      If `source` contains a definition of a given symbol that has a doc comment,
   *                      the (expanded, raw, position) triplet for a comment, otherwise ("", "", NoPosition).
   *  Note: This operation does not automatically load sources that are not yet loaded.
   */
  def asyncDocComment(sym: Symbol, source: SourceFile, site: Symbol, fragments: List[(Symbol,SourceFile)]): Response[(String, String, Position)]

  /** Ask a fresh type-checking round on all loaded compilation units. */
  def askReloadManagedUnits(): Unit

  /** Start a new type-checking round for changes in loaded compilation units.
   *
   *  Unlike `askReloadManagedUnits`, this one causes a reload of only units that have
   *  changes that were not yet re-type-checked.
   */
  def flushScheduledReloads(): Response[Unit]

  /** Return a list of all loaded compilation units */
  def compilationUnits: List[InteractiveCompilationUnit]

  /** Add a compilation unit (CU) to the set of CUs to be Reloaded at the next refresh round.
   */
  def scheduleReload(icu: InteractiveCompilationUnit, contents: SourceFile): Unit

  /** Reload the given compilation unit. If the unit is not tracked by the presentation
   *  compiler, it will be from now on.
   */
  def askReload(scu: InteractiveCompilationUnit, content: SourceFile): Response[Unit]

  /** Atomically load a list of units in the current presentation compiler. */
  def askReload(units: List[InteractiveCompilationUnit]): Response[Unit]

  /** Stop compiling the given unit. Usually called when the user
   *  closed an editor.
   */
  def discardCompilationUnit(scu: InteractiveCompilationUnit): Unit

  /** Tell the presentation compiler to refresh the given files,
   *  if they are not managed by the presentation compiler already.
   *
   *  This is usually called when files changed on disk and the associated compiler
   *  symbols need to be refreshed. For example, a git checkout will trigger
   *  such a call.
   */
  def refreshChangedFiles(files: List[IFile]): Unit

  /** Return the compilation errors for the given unit. It will block until the
   *  type-checker finishes (but subsequent calls are fast once the type-checker finished).
   *
   *  @note This method does not trigger a fresh type-checking round on its own. Instead,
   *        it reports compiler errors/warnings from the last type-checking round.
   */
  def problemsOf(scu: InteractiveCompilationUnit): List[ScalaCompilationProblem]

  /** Find the definition of given symbol. Returns a compilation unit and an offset in that unit.
   *
   *  @note The offset is relative to the Scala source file represented by the given unit. This may
   *        be different from the absolute offset in the workspace file of that unit if the unit is
   *        not a Scala source file. For example, Play templates are translated on the fly to Scala
   *        sources. The returned offset would be relative to the Scala translation, and would need
   *        to be mapped back to the Play template offset before being used to reveal an editor
   *        location.
   */
  def findDeclaration(sym: Symbol, javaProject: IJavaProject): Option[(InteractiveCompilationUnit, Int)]

  /** Return the JDT element corresponding to this Scala symbol. This method is time-consuming
   *  and may trigger building the structure of many Scala files.
   *
   *  @param sym      The symbol to map to a Java element.
   *  @param projects A number of projects to look for Java elements. It is important
   *                  that you limit the search to the smallest number of projects possible.
   *                  This search is an exhaustive search, and if no projects are specified
   *                  it uses all Java projects in the workspace. Usually, a single projects
   *                  is passed.
   */
  def getJavaElement(sym: Symbol, projects: IJavaProject*): Option[IJavaElement]

  /** Return the compilation unit (Scala File) in which the symbol passed as an argument is defined.
   *  This method is time-consuming and may trigger building the structure of many Scala files.
   *
   *  @param sym      The symbol to seek a compilation unit for.
   */
  def findCompilationUnit(sym: Symbol, javaProject: IJavaProject): Option[InteractiveCompilationUnit]

  /** Return the parsed scaladoc comment available at the definition of a Scala symbol.
   *
   *  @param sym      The symbol to find documentation for.
   *  @param site     The symbol which declaration contains the declaration of that member,
   *                  often its enclosing class.
   */
  def parsedDocComment(sym: Symbol, site: Symbol, javaProject: IJavaProject): Option[Comment]

  /** Return a type info string for a symbol and its type
   *
   *  @param sym      The symbol to return type info on
   *  @param tpe      The symbol's type
   */
  def headerForSymbol(sym:Symbol, tpe:Type): Option[String]

  /** Create a Scala CompletionProposal. This method is the exit point from the compiler cake,
   *  extracting all the information needed from compiler Symbols and Types to present a completion
   *  option to the user.
   *
   *  @note The resulting type does not have any path-dependent types coming from the
   *        compiler instance.
   *
   *  @param prefix    The prefix typed by the user at the point where he asked for completion
   *  @param start     The position where the completion should be inserted (usually the beginning of `prefix`)
   *  @param sym       The symbol corresponding to this completion proposal
   *  @param tpe       The type of the given symbol. This is usually more precise than the type of `sym`, taking into
   *                   account the context where the symbol will be inserted (type parameters may be instantiated).
   *  @param inherited Whether the symbol was inherited
   *  @param viaView   If the symbol is added by an implicit conversion, the symbol of that method
   *  @param context   The context in which completion is invoked
   *  @param project   The project in which additional info can be searched for (like parameter names for Java methods)
   *
   *  @see CompletionProposal
   */
  def mkCompletionProposal(prefix: String,
    start: Int,
    sym: Symbol,
    tpe: Type,
    inherited: Boolean,
    viaView: Symbol,
    context: CompletionContext.ContextType,
    project: IScalaProject): CompletionProposal

  /** Create a hyperlink to the given symbol. This is an exit point from the compiler cake.
   *
   *  @note The resulting type does not have any path-dependent types coming from the
   *        compiler instance.
   *
   * @param sym         The symbol definition to which the hyperlink should go
   * @param name        The primary information to be displayed, if more than one hyperlink is available
   * @param region      The region to be underlined in the editor
   * @param javaProject The java project where to search for the definition of this symbol
   * @param label       A way to compute the attached hyperlink label. Normally this can be ignored and use the default label,
   *                    consisting of the symbol kind and full name.
   */
  def mkHyperlink(sym: Symbol, name: String, region: IRegion, javaProject: IJavaProject, label: Symbol => String = defaultHyperlinkLabel _): Option[IHyperlink]
}

object IScalaPresentationCompiler extends HasLogger {
  /** The maximum time to wait for an `askOption` call to finish. */
  final val AskTimeout: Duration = if (IScalaPlugin().noTimeoutMode) Duration.Inf else 10000.millis

  /** Convenience method for creating a Response */
  def withResponse[A](op: Response[A] => Any): Response[A] = {
    val response = new Response[A]
    op(response)
    response
  }

  object Implicits {
    implicit class RichResponse[A](val resp: Response[A]) extends AnyVal {

      /** Extract the value from this response, blocking the calling thread.
       *
       *  @param default The default value to be returned in case the underlying Response failed
       *                 or a timeout occurred
       *
       *  Clients should always specify a timeout value when calling this method. In rare cases
       *  a response is never completed (for example, when the presentation compiler restarts).
       *
       *  Failures are logged:
       *   - TypeError and FreshRunReq are printed to stdout, all the others are logged in the platform error log.
       */
      def getOrElse[B >: A](default: => B)(timeout: Duration = AskTimeout): B = {
        getOption(timeout).getOrElse(default)
      }

      /** Extract the value from this response, blocking the calling thread.
       *
       *  Clients should always specify a timeout value when calling this method. In rare cases
       *  a response is never completed (for example, when the presentation compiler restarts).
       *
       *  Failures are logged:
       *   - TypeError and FreshRunReq are printed to stdout, all the others are logged in the platform error log.
       */
      def getOption(timeout: Duration = AskTimeout): Option[A] = {
        val res = if (IScalaPlugin().noTimeoutMode) Some(resp.get) else resp.get(timeout.toMillis)

        res match {
          case None =>
            eclipseLog.info("Timeout in askOption", new Throwable) // log a throwable for its stacktrace
            None

          case Some(result) =>
            result match {
              case Right(fi: FailedInterrupt) =>
                fi.getCause() match {
                  case e: Global#TypeError     => logger.info("TypeError in ask:\n", e)
                  case f: FreshRunReq          => logger.info("FreshRunReq in ask:\n", f)
                  case m: MissingResponse      => logger.info("MissingResponse in ask. Called from: ", m)
                  // This can happen if you ask long queries of the
                  // PC, triggering long sleep() sessions on caller
                  // side.
                  case i: InterruptedException => logger.debug("InterruptedException in asyncExec", i) // no need to call `interrupt`, `Response` already did it.
                  case e                       => eclipseLog.error("Error during asyncExec (FailedInterrupt)", e)
                }
                None

              case Right(m: MissingResponse) =>
                logger.info("MissingResponse in ask. Called from: ", m)
                None

              case Right(ie: InterruptedException) =>
                logger.error("Ignoring InterruptException")
                Thread.currentThread().interrupt()
                None

              case Right(f: FreshRunReq)          =>
                logger.info("FreshRunReq in ask:\n", f)
                None

              case Right(e: Throwable) =>
                eclipseLog.error("Throwable during asyncExec", e)
                None

              case Left(v) => Some(v)
            }
        }
      }
    }
  }
}
