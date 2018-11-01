package os

import scala.util.Try


/**
  * A path which is either an absolute [[Path]], a relative [[RelPath]],
  * or a [[ResourcePath]] with shared APIs and implementations.
  *
  * Most of the filesystem-independent path-manipulation logic that lets you
  * splice paths together or navigate in and out of paths lives in this interface
  */
trait BasePath{
  type ThisType <: BasePath
  /**
    * The individual path segments of this path.
    */
  def segments: IndexedSeq[String]

  /**
    * Combines this path with the given relative path, returning
    * a path of the same type as this one (e.g. `Path` returns `Path`,
    * `RelPath` returns `RelPath`
    */
  def /(subpath: RelPath): ThisType

  /**
    * Relativizes this path with the given `base` path, finding a
    * relative path `p` such that base/p == this.
    *
    * Note that you can only relativize paths of the same type, e.g.
    * `Path` & `Path` or `RelPath` & `RelPath`. In the case of `RelPath`,
    * this can throw a [[PathError.NoRelativePath]] if there is no
    * relative path that satisfies the above requirement in the general
    * case.
    */
  def relativeTo(target: ThisType): RelPath

  /**
    * This path starts with the target path, including if it's identical
    */
  def startsWith(target: ThisType): Boolean

  /**
    * The last segment in this path. Very commonly used, e.g. it
    * represents the name of the file/folder in filesystem paths
    */
  def last: String

  /**
    * Gives you the file extension of this path, or the empty
    * string if there is no extension
    */
  def ext: String
}

object BasePath {
  def checkSegment(s: String) = {
    def fail(msg: String) = throw PathError.InvalidSegment(s, msg)
    def considerStr =
      "use the Path(...) or RelPath(...) constructor calls to convert them. "

    s.indexOf('/') match{
      case -1 => // do nothing
      case c => fail(
        s"[/] is not a valid character to appear in a path segment. " +
          "If you want to parse an absolute or relative path that may have " +
          "multiple segments, e.g. path-strings coming from external sources " +
          considerStr
      )

    }
    def externalStr = "If you are dealing with path-strings coming from external sources, "
    s match{
      case "" =>
        fail(
          "Ammonite-Ops does not allow empty path segments " +
            externalStr + considerStr
        )
      case "." =>
        fail(
          "Ammonite-Ops does not allow [.] as a path segment " +
            externalStr + considerStr
        )
      case ".." =>
        fail(
          "Ammonite-Ops does not allow [..] as a path segment " +
            externalStr +
            considerStr +
            "If you want to use the `..` segment manually to represent going up " +
            "one level in the path, use the `up` segment from `os.up` " +
            "e.g. an external path foo/bar/../baz translates into 'foo/'bar/up/'baz."
        )
      case _ =>
    }
  }
  def chunkify(s: java.nio.file.Path) = {
    import collection.JavaConverters._
    s.iterator().asScala.map(_.toString).filter(_ != ".").filter(_ != "").toArray
  }
}

trait BasePathImpl extends BasePath{
  def segments: IndexedSeq[String]

  protected[this] def make(p: Seq[String], ups: Int): ThisType

  def /(subpath: RelPath) = make(
    segments.dropRight(subpath.ups) ++ subpath.segments,
    math.max(subpath.ups - segments.length, 0)
  )

  def ext = {
    if (!segments.last.contains('.')) ""
    else segments.last.split('.').lastOption.getOrElse("")
  }

  def last = segments.last
}

object PathError{
  type IAE = IllegalArgumentException
  private[this] def errorMsg(s: String, msg: String) =
    s"[$s] is not a valid path segment. $msg"

  case class InvalidSegment(segment: String, msg: String) extends IAE(errorMsg(segment, msg))

  case object AbsolutePathOutsideRoot
    extends IAE("The path created has enough ..s that it would start outside the root directory")

  case class NoRelativePath(src: RelPath, base: RelPath)
    extends IAE(s"Can't relativize relative paths $src from $base")
}

/**
  * Represents a value that is either an absolute [[Path]] or a
  * relative [[RelPath]], and can be constructed from a
  * java.nio.file.Path or java.io.File
  */
trait FilePath extends BasePath
object FilePath {
  def apply[T: PathConvertible](f0: T) = {
    val f = implicitly[PathConvertible[T]].apply(f0)
    if (f.isAbsolute) Path(f0)
    else RelPath(f0)
  }
}

/**
  * A relative path on the filesystem. Note that the path is
  * normalized and cannot contain any empty or ".". Parent ".."
  * segments can only occur at the left-end of the path, and
  * are collapsed into a single number [[ups]].
  */
class RelPath private[os](segments0: Array[String], val ups: Int)
  extends FilePath with BasePathImpl{
  val segments: IndexedSeq[String] = segments0
  type ThisType = RelPath
  require(ups >= 0)
  protected[this] def make(p: Seq[String], ups: Int) = {
    new RelPath(p.toArray[String], ups + this.ups)
  }

  def relativeTo(base: RelPath): RelPath = {
    if (base.ups < ups) {
      new RelPath(segments0, ups + base.segments.length)
    } else if (base.ups == ups) {
      val commonPrefix = {
        val maxSize = scala.math.min(segments0.length, base.segments.length)
        var i = 0
        while ( i < maxSize && segments0(i) == base.segments(i)) i += 1
        i
      }
      val newUps = base.segments.length - commonPrefix

      new RelPath(segments0.drop(commonPrefix), ups + newUps)
    } else throw PathError.NoRelativePath(this, base)
  }

  def startsWith(target: RelPath) = {
    this.segments0.startsWith(target.segments) && this.ups == target.ups
  }

  override def toString = (Seq.fill(ups)("..") ++ segments0).mkString("/")
  override def hashCode = segments.hashCode() + ups.hashCode()
  override def equals(o: Any): Boolean = o match {
    case p: RelPath => segments == p.segments && p.ups == ups
    case _ => false
  }
}

object RelPath {
  def apply[T: PathConvertible](f0: T): RelPath = {
    val f = implicitly[PathConvertible[T]].apply(f0)

    require(!f.isAbsolute, f + " is not an relative path")

    val segments = BasePath.chunkify(f.normalize())
    val (ups, rest) = segments.partition(_ == "..")
    new RelPath(rest, ups.length)
  }

  implicit def SymPath(s: Symbol): RelPath = StringPath(s.name)
  implicit def StringPath(s: String): RelPath = {
    BasePath.checkSegment(s)
    new RelPath(Array(s), 0)

  }
  def apply(segments0: IndexedSeq[String], ups: Int) = {
    segments0.foreach(BasePath.checkSegment)
    new RelPath(segments0.toArray, ups)
  }

  implicit def SeqPath[T](s: Seq[T])(implicit conv: T => RelPath): RelPath = {
    s.foldLeft(rel){_ / _}
  }

  implicit def ArrayPath[T](s: Array[T])(implicit conv: T => RelPath): RelPath = SeqPath(s)

  implicit val relPathOrdering: Ordering[RelPath] =
    Ordering.by((rp: RelPath) => (rp.ups, rp.segments.length, rp.segments.toIterable))

  val up: RelPath = new RelPath(Array.empty[String], 1)
  val rel: RelPath = new RelPath(Array.empty[String], 0)
}

object Path {
  def apply(p: FilePath, base: Path) = p match{
    case p: RelPath => base/p
    case p: Path => p
  }

  def apply[T: PathConvertible](f: T, base: Path): Path = apply(FilePath(f), base)
  def apply[T: PathConvertible](f0: T): Path = {
    val f = implicitly[PathConvertible[T]].apply(f0)

    val chunks = BasePath.chunkify(f)
    if (chunks.count(_ == "..") > chunks.size / 2) throw PathError.AbsolutePathOutsideRoot

    require(f.isAbsolute, f + " is not an absolute path")
    new Path(f.getRoot, BasePath.chunkify(f.normalize()))
  }

  implicit val pathOrdering: Ordering[Path] =
    Ordering.by((rp: Path) => (rp.segments.length, rp.segments.toIterable))
}

/**
  * An absolute path on the filesystem. Note that the path is
  * normalized and cannot contain any empty `""`, `"."` or `".."` segments
  */
class Path private[os](val root: java.nio.file.Path, segments0: Array[String])
  extends FilePath with BasePathImpl with SeekableSource{
  val segments: IndexedSeq[String] = segments0
  def getHandle() = Right(java.nio.file.Files.newByteChannel(toNIO))
  type ThisType = Path

  def toNIO = root.resolve(segments0.mkString(root.getFileSystem.getSeparator))

  protected[this] def make(p: Seq[String], ups: Int) = {
    if (ups > 0) throw PathError.AbsolutePathOutsideRoot
    new Path(root, p.toArray[String])
  }
  override def toString = toNIO.toString

  override def equals(o: Any): Boolean = o match {
    case p: Path => segments == p.segments
    case _ => false
  }
  override def hashCode = segments.hashCode()

  def startsWith(target: Path) = segments0.startsWith(target.segments)

  def relativeTo(base: Path): RelPath = {
    var newUps = 0
    var s2 = base.segments

    while(!segments0.startsWith(s2)){
      s2 = s2.dropRight(1)
      newUps += 1
    }
    new RelPath(segments0.drop(s2.length), newUps)
  }

  def toIO = toNIO.toFile
}

sealed trait PathConvertible[T]{
  def apply(t: T): java.nio.file.Path
}

object PathConvertible{
  implicit object StringConvertible extends PathConvertible[String]{
    def apply(t: String) = java.nio.file.Paths.get(t)
  }
  implicit object JavaIoFileConvertible extends PathConvertible[java.io.File]{
    def apply(t: java.io.File) = java.nio.file.Paths.get(t.getPath)
  }
  implicit object NioPathConvertible extends PathConvertible[java.nio.file.Path]{
    def apply(t: java.nio.file.Path) = t
  }
}