package com.tribbloids.spookystuff.parsing

// TODO: generalise!
trait PhaseVec

object PhaseVec {

  object InitialPhaseVec extends PhaseVec

  case class NoOp(
      skipOpt: Option[Int] = None
  ) extends PhaseVec {

//    def next(bm: BacktrackingManager#LinearSearch): Option[Like] = {
//
//      skipOpt match {
//        case Some(skip) => bm.length_+=(skip + 1)
//        case None       => bm.transitionQueueII += 1 //TODO: is it really useful?
//      }
//      None
//    }
  }

//  trait Transition extends Like {
//
//    def next(bm: BacktrackingManager#LinearSearch): Option[Like] = {
//
//      bm.transitionQueueII += 1
//      bm.currentOutcome = transition._1 -> nextResult
//      return transition._2 -> nextResult.nextPhaseVecOpt.asInstanceOf[PhaseVec]
//    }
//
//  }
}
