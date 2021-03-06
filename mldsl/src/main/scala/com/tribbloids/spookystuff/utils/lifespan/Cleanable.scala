package com.tribbloids.spookystuff.utils.lifespan

import com.tribbloids.spookystuff.utils.CachingUtils._
import com.tribbloids.spookystuff.utils.TreeThrowable
import org.slf4j.LoggerFactory

import scala.reflect.ClassTag
import scala.util.Try

object Cleanable {

  import com.tribbloids.spookystuff.utils.CommonViews._

  type TrackingN = Long
  type InBatchMap = ConcurrentCache[Long, Cleanable]

  val uncleaned: ConcurrentMap[Any, InBatchMap] = ConcurrentMap()

  def getOrNew(id: Any): InBatchMap = {

    uncleaned.getOrElseUpdateSynchronously(id) {

      ConcurrentMap()
    }
  }

  def getByLifespan(
      id: Any,
      condition: Cleanable => Boolean
  ): (InBatchMap, List[Cleanable]) = {
    val batch = uncleaned.getOrElse(id, ConcurrentMap())
    val filtered = batch.values.toList //create deep copy to avoid in-place deletion
      .filter(condition)
    (batch, filtered)
  }
  def getAll(
      condition: Cleanable => Boolean = _ => true
  ): Seq[Cleanable] = {
    uncleaned.values.toList.flatten
      .map(_._2)
      .filter(condition)
  }
  def getTyped[T <: Cleanable: ClassTag]: Seq[T] = {
    val result = getAll {
      case _: T => true
      case _    => false
    }.map { v =>
      v.asInstanceOf[T]
    }
    result
  }

  // cannot execute concurrent
  def cleanSweep(
      id: Any,
      condition: Cleanable => Boolean = _ => true
  ): Unit = {

    val (map, filtered) = getByLifespan(id, condition)
    filtered
      .foreach { instance =>
        instance.tryClean()
      }
    map --= filtered.map(_.trackingNumber)
    if (map.isEmpty) uncleaned.remove(id)
  }

  def cleanSweepAll(
      condition: Cleanable => Boolean = _ => true
  ): Unit = {

    uncleaned.keys.toList
      .foreach { tt =>
        cleanSweep(tt, condition)
      }
  }
}

/**
  * This is a trait that unifies resource cleanup on both Spark Driver & Executors
  * instances created on Executors are cleaned by Spark TaskCompletionListener
  * instances created otherwise are cleaned by JVM shutdown hook
  * finalizer helps but is not always reliable
  * can be serializable, but in which case implementation has to allow deserialized copy on a different machine to be cleanable as well.
  */
trait Cleanable {

  import Cleanable._

  @transient object CleanStateLock

  /**
    * taskOrThreadOnCreation is incorrect in withDeadline or threads not created by Spark
    * Override this to correct such problem
    */
  def _lifespan: Lifespan = new Lifespan.JVM()
  final val lifespan = _lifespan
  final val trackingNumber = System.identityHashCode(this).toLong // can be int value

  //each can only be cleaned once
  @volatile protected var _isCleaned: Boolean = false
  def isCleaned: Boolean = CleanStateLock.synchronized {
    _isCleaned
  }

  @volatile var stacktraceAtCleaning: Option[Array[StackTraceElement]] = None

  @transient lazy val uncleanedInBatchs: Seq[InBatchMap] = {
    // This weird implementation is to mitigate thread-unsafe competition:
    // 2 empty collections being inserted simultaneously
    lifespan.batchIDs.map { id =>
      Cleanable.getOrNew(id)
    }

  }

  {
    logPrefixed("Created")
    uncleanedInBatchs.foreach { inBatch =>
      inBatch += this.trackingNumber -> this
    }
  }

  def logPrefix: String = {
    s"$trackingNumber @ ${lifespan.toString} \t| "
  }
  protected def logPrefixed(s: String): Unit = {
    LoggerFactory.getLogger(this.getClass).debug(s"$logPrefix $s")
  }

  /**
    * can only be called once
    */
  protected def cleanImpl(): Unit

  def assertNotCleaned(errorInfo: String): Unit = {
    assert(
      !isCleaned,
      s"$logPrefix $errorInfo: $this is already cleaned @\n" +
        s"${stacktraceAtCleaning.get.mkString("\n")}"
    )
  }

  def chainClean: Seq[Cleanable] = Nil

  def clean(silent: Boolean = false): Unit = {
    val chained: Seq[Try[Unit]] = chainClean.map { v =>
      Try {
        v.clean(silent)
      }
    }
    val self = CleanStateLock.synchronized {
      Try {
        if (!isCleaned) {
          stacktraceAtCleaning = Some(Thread.currentThread().getStackTrace)
          try {
            cleanImpl()
            _isCleaned = true
            if (!silent) logPrefixed("Cleaned")
          } catch {
            case e: Throwable =>
              stacktraceAtCleaning = None
              throw e
          }
        }
      }
    }

    TreeThrowable.&&&(chained :+ self)

    uncleanedInBatchs.foreach { inBatch =>
      inBatch -= this.trackingNumber
    }
  }

  def isSilent(ee: Throwable): Boolean = false

  def tryClean(silent: Boolean = false): Unit = {
    try {
      clean(silent)
    } catch {
      case e: Throwable =>
        val ee = e
        if (!isSilent(ee))
          LoggerFactory
            .getLogger(this.getClass)
            .warn(
              s"$logPrefix !!! FAIL TO CLEAN UP !!!\n",
              ee
            )
    } finally {
      super.finalize()
    }
  }

  override protected def finalize(): Unit = tryClean()
}
