package org.apache.spark.ml.dsl

import java.util.UUID

import com.tribbloids.spookystuff.graph.{DataAlgebra, GraphSystem, IDAlgebra}

class MLSystem extends GraphSystem {

  override type ID = UUID
  override type NodeData = NamedStage
  override type EdgeData = Connector

}

object MLSystem extends GraphSystem.Builder[MLSystem] {

  override def idAlgebra: IDAlgebra[UUID] = IDAlgebra.ForUUID

  override object edgeAlgebra extends DataAlgebra[Connector] {

    override val eye: Connector = PASSTHROUGH

    override def combine(
        v1: Connector,
        v2: Connector
    ): Connector = {
      (v1, v2) match {
        case (_, PASSTHROUGH) => v1
        case (PASSTHROUGH, _) => v2
        //        case (v1: Source, v2: Source) => v1 //TODO: doesn't look right
      }
    }
  }
}