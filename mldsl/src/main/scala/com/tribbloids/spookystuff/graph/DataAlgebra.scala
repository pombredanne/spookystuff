package com.tribbloids.spookystuff.graph

trait DataAlgebra[T] {

  def eye: T = throw new UnsupportedOperationException(s"${this.getClass.getName} has no identity defined")

  def +(v1: T, v2: T): T

  object Monadic extends DataAlgebra[Option[T]] {

    override val eye = None

    override def +(v1: Option[T], v2: Option[T]): Option[T] = {
      (v1, v2) match {
        case (Some(x), Some(y)) => Some(DataAlgebra.this.+(x, y))
        case (Some(x), None)    => Some(x)
        case (None, Some(y))    => Some(y)
        case _                  => None
      }
    }
  }
}

object DataAlgebra {

  case class NoAmbiguity[T](eyeOpt: Option[T] = None) extends DataAlgebra[T] {

    override def eye: T = eyeOpt.getOrElse(super.eye)

    override def +(v1: T, v2: T): T = {
      if (v1 == v2) v1
      else throw new UnsupportedOperationException(s"conflict between data '$v1' and '$v2' which has identical IDs")
    }
  }
}
