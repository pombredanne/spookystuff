package com.tribbloids.spookystuff.utils.io

import java.util.UUID

import com.tribbloids.spookystuff.utils.lifespan.{Lifespan, LocalCleanable}
import com.tribbloids.spookystuff.utils.{CachingUtils, CommonUtils, NoRetry}

// stage by moving to a secret location
case class Snapshot(
    original: URIResolver#URISession,
    expire: Obsolescence = URIResolver.default.expire,
    override val _lifespan: Lifespan = Lifespan.JVM()
) extends LocalCleanable {

  import Snapshot._

  val resolver: URIResolver = original.outer

  object tempDir {

    lazy val pathStr: String = original.absolutePathStr + SUFFIX

    lazy val session: resolver.URISession = resolver.newSession(pathStr)
  }

  lazy val oldFilePath: String = original.absolutePathStr + OLD_SUFFIX

  object tempFile {

    lazy val createTime: Long = System.currentTimeMillis()

    lazy val pathStr: String = {

      val name = toFileName(createTime)
      val path = CommonUtils./:/(tempDir.pathStr, name)
      path
    }

    lazy val session: resolver.URISession = resolver.newSession(pathStr)

    def toFileName(time: Long): String = {
      val uuid = UUID.randomUUID()
      s"$uuid.T$time"
    }
    def fromFileName(v: String): Option[Long] = {
      val parts = v.split('.')

      if (parts.length != 2) return None

      val ext = parts.last

      if (!ext.startsWith("T")) {
        None
      }

      Some(ext.stripPrefix("T").toLong)
    }
  }

  def during[T](fn: URISession => T): T = {
    resolver.retry {
      duringOnce(fn)
    }
  }

  def duringOnce[T](fn: URISession => T): T = {
    val snapshotSession: LazySnapshotSession = acquireSession()

    val result = try {
      fn(snapshotSession)
    } catch {
      case e: ResourceLockError => throw e
      case e @ _                => throw NoRetry(e)
    }

    snapshotSession.commitBack() // point of no return

    succeed()

    result
  }

  case class LazySnapshotSession() extends resolver.URISession {

    val originalSignature: IntegritySignature = getIntegritySignature()

    def getIntegritySignature(session: URISession = original): IntegritySignature = {
      session.input { in =>
        if (in.isExisting) Some(IntegritySignature.V(in.getLastModified, in.getLength))
        else None
      }
    }

    lazy val tempSession: resolver.URISession = tempFile.session

    @transient var tempFileInitialized: Boolean = false
    @transient var tempFileModified: Boolean = false

    protected def needOverwrittenFile[T](overwriteFn: () => T): T = {

      if (!tempFileInitialized) {

        if (original.isExisting) {
          //        println(s"empty snapshot: ${delegate.absolutePathStr}")
          tempSession.output(WriteMode.CreateOnly)(_.stream)
        } else {
          //          println(s"dir only: ${TempFile.session.absolutePathStr}")
          tempSession.output(WriteMode.CreateOnly)(_.stream)
          tempSession.delete() // just leaving the directory
        }

        val delegateSignature = getIntegritySignature(tempSession)

        try {
          val result = overwriteFn()

          tempFileInitialized = true
          result
        } catch {
          case e: Throwable =>
            if (delegateSignature != getIntegritySignature(tempSession)) {
              // overwrite is partially successful
              tempFileInitialized = true
            } else {
              // overwrite doesn't do a thing
              tempSession.delete(false)
            }
            throw e
        }

      } else {
        overwriteFn()
      }
    }

    // takes a copy
    protected def needOriginalFile(): Unit = {

      if (!tempFileInitialized) {

        if (original.isExisting) {
          //        println(s"real snapshot: ${delegate.absolutePathStr}")
          original.copyTo(tempSession.absolutePathStr, WriteMode.CreateOnly)
        } else {
          //          println(s"dir only: ${TempFile.session.absolutePathStr}")
          tempSession.output(WriteMode.CreateOnly)(_.stream)
          tempSession.delete() // just leaving the directory
        }
      }

      checkIntegrity() // otherwise the copy may be obsolete

      tempFileInitialized = true
    }

    // just a file rotation, 2 moves only
    def commitBack(): Unit = {

      if (tempSession.isExisting) {

        if (tempFileModified) {

          checkIntegrity()

          if (original.isExisting)
            original.moveTo(oldFilePath)

          tempSession.moveTo(original.absolutePathStr)
        } else {
          tempSession.delete(false)
        }
      }

    }

    def checkIntegrity(): Unit = {

      try {

        // ensure that all snapshots in it are either obsolete or have a later create time

        // ensure that all snapshots in it are obsolete
        val existingTempFiles = tempDir.session.input(in => in.children)
        val notExpired = expire.filter(existingTempFiles)
        val notExpiredOrSelf = notExpired.filter { session =>
          session.absolutePathStr != tempFile.session.absolutePathStr
        }

        notExpiredOrSelf.foreach { ff =>
          val fileName = ff.absolutePathStr.split('/').last
          val createTime = tempFile.fromFileName(fileName).getOrElse {
            abort(
              s"""
                 |snapshot directory has been corrupted and contains an illegal file:
                 |$fileName
                 |""".stripMargin
            )
          }

          if (createTime <= tempFile.createTime)
            abort(
              s"""
                 |another snapshot file '${ff.absolutePathStr}' has been created,
                 |likely before the current snapshot being created at T${tempFile.createTime}
                 |""".stripMargin
            )
        //          else {
        //            println("")
        //          }
        }
      } catch {
        case e: Throwable =>
          abort(e.getMessage)
      }

      Thread.sleep(10)
      //WARNING: DO NOT DELETE! this line eliminates race condition between file renaming & both checks

      val updatedSignature = getIntegritySignature()
      if (this.originalSignature != updatedSignature)
        abort(
          s"""
             |file integrity signature has been updated:
             |original : $originalSignature
             |updated  : $updatedSignature
             |""".stripMargin
        )
    }

    override def absolutePathStr: String = tempSession.absolutePathStr

    override protected[io] def _delete(mustExist: Boolean): Unit = {

      needOverwrittenFile { () =>
        tempSession.delete(mustExist)
      }
      tempFileModified = true
    }

    override def moveTo(target: String): Unit = {

      needOriginalFile()

      tempSession.moveTo(target)
      tempFileModified = true
    }

    override def input[T](fn: InputResource => T): T = {

      needOriginalFile()

      tempSession.input(fn)
    }

    override def output[T](mode: WriteMode)(fn: OutputResource => T): T = {

      val result = mode match {
        case WriteMode.Overwrite | WriteMode.CreateOnly =>
          var result: Option[T] = None
          needOverwrittenFile { () =>
            result = Some(tempSession.output(mode)(fn))
          }
          result.get
        case WriteMode.Append =>
          needOriginalFile()

          tempSession.output(mode)(fn)
      }

      tempFileModified = true

      result
    }
  }

  def acquireSession(): LazySnapshotSession = {

    checkAllSnapshotsExpired()

    LazySnapshotSession()
  }

  def checkAllSnapshotsExpired(): Unit = {

    // ensure that all snapshots in it are obsolete
    val existingTempFiles = tempDir.session.input { in =>
      if (!in.isExisting) return // no need to check

      if (!in.isDirectory)
        abort(
          s"""
             |snapshot directory should not be a file:
             |${tempDir.session}
             |""".stripMargin
        )
      in.children
    }
    val notExpired = expire.filter(existingTempFiles)

    notExpired.foreach { ff =>
      val elapsedOpt = expire.checkSession(ff)
      elapsedOpt.foreach { v =>
        abort(
          s"another snapshot file '${ff.absolutePathStr}' has existed for ${v.elapsedMillis} milliseconds"
        )
      }
    }
  }

  def succeed(): Unit = {
    tempDir.session.delete()
  }

  def abort(info: String): Nothing = {
    abortSilently()
    throw new ResourceLockError("Abort: " + info.trim)
  }
  def abortSilently(): Unit = {
    tempFile.session.delete(false)
  }
  override protected def cleanImpl(): Unit = {
    abortSilently()
  }
}

object Snapshot {

  val acquired: CachingUtils.ConcurrentMap[URIResolver#URISession, Long] = CachingUtils.ConcurrentMap()

  final val SUFFIX: String = ".lock"

  final val OLD_SUFFIX: String = ".old"

  final val CREATED: String = ".created"

  object IntegritySignature {
    case class V(
        lastModified: Long,
        length: Long
    )
  }

  type IntegritySignature = Option[IntegritySignature.V]

  //  case class IntegritySignature(
  //      lastModified: Long,
  //      length: Long
  //  )
}