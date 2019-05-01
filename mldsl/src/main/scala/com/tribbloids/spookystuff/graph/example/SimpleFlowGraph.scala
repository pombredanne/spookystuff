package com.tribbloids.spookystuff.graph.example

import java.util.UUID

import com.tribbloids.spookystuff.graph._

trait SimpleFlowGraph extends Domain {

  override type ID = UUID
  override type NodeData = Option[String]
  override type EdgeData = Option[String]
}

object SimpleFlowGraph extends Algebra[SimpleFlowGraph] {

  override def idAlgebra = IDAlgebra.UUIDAlgebra

  object DataAlgebraProto extends DataAlgebra[String] {

    override def +(v1: String, v2: String): String = v1 + v2
  }

  override def nodeAlgebra = DataAlgebra.NoAmbiguity().Monadic
  override def edgeAlgebra = DataAlgebraProto.Monadic

  object Layout extends FlowLayout[SimpleFlowGraph] {

    override lazy val defaultGraphBuilder: LocalGraph.BuilderImpl[SimpleFlowGraph] = LocalGraph.BuilderImpl()

    override lazy val defaultFormat = Formats.ShowOption
  }

  object DSL extends Layout.DSL {

    case class Operand[+M <: _Module](core: Layout.Core[M]) extends OperandLike[M] {}

    override def create[M <: _Module](core: Layout.Core[M]): Operand[M] = Operand(core)
  }
}