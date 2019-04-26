package com.tribbloids.spookystuff.parsing

import java.util.UUID

import com.tribbloids.spookystuff.graph._

/**
  * State-machine based parser combinator that takes a graph and compile into a deterministic decision process
  * ... that consumes a stream of tokens and mutates state/output
  */
trait FSMParserGraph extends Domain {

  override type ID = UUID
  override type NodeData = FState.Type
  override type EdgeData = Option[Rule]
}

object FSMParserGraph extends Algebra[FSMParserGraph] {

  override def idAlgebra = IDAlgebra.UUIDAlgebra

  override def nodeAlgebra = DataAlgebra.NoAmbiguity(Some(FState.Ordinary))
  override def edgeAlgebra = DataAlgebra.NoAmbiguity().Monadic

  object Layout extends FlowLayout[FSMParserGraph] {

    override lazy val defaultGraphBuilder: LocalGraph.BuilderImpl[FSMParserGraph] = LocalGraph.BuilderImpl()

    override lazy val defaultFormat: Layout.Formats.ShowOption.type = Formats.ShowOption
  }
}
