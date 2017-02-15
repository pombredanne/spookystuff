package com.tribbloids.spookystuff.uav

import com.tribbloids.spookystuff.uav.dsl._
import com.tribbloids.spookystuff.uav.sim.APMSim
import com.tribbloids.spookystuff.uav.spatial.{GeodeticAnchor, Location}
import com.tribbloids.spookystuff.uav.system.Drone
import com.tribbloids.spookystuff.{ModuleConf, Submodules}
import org.apache.spark.SparkConf

import scala.concurrent.duration._

object UAVConf extends Submodules.Builder[UAVConf]{

  //DO NOT change to val! all confs are mutable
  def default = UAVConf()

  final val DEFAULT_BAUDRATE = 57600
  //  final val DEFAULT_BAUDRATE = 9200 // for testing only

  final val EXECUTOR_SSID = 250
  final val PROXY_SSID = 251
  final val GCS_SSID = 255

  //  final val EARTH_RADIUS = 6378137.0  // Radius of "spherical" earth

  final val FAST_CONNECTION_RETRIES = 2
  final val BLACKLIST_RESET_AFTER = 1.minute
}

/**
  * Created by peng on 04/09/16.
  */
case class UAVConf(
                    // list all possible connection string of drones
                    // including tcp, udp and serial,
                    // some of them may be unreachable but you don't care.
                    // connection list is configed by user and shared by all executors
                    // blacklist is node specific and determined by GenPartitioner
                    // routing now becomes part of Connection?
                    var fleet: Fleet = Fleet.Inventory(Nil),
                    var linkFactory: LinkFactory = LinkFactories.ForkToGCS(),
                    var fastConnectionRetries: Int = UAVConf.FAST_CONNECTION_RETRIES,
                    var slowConnectionRetries: Int = Int.MaxValue,
                    var slowConnectionRetryInterval: Duration = UAVConf.BLACKLIST_RESET_AFTER, //1 minute
                    var clearanceAltitude: Double = 10, // in meters
                    var homeLocation: Location = APMSim.HOME_LLA -> GeodeticAnchor,
                    var actionCosts: ActionCosts = {_ => 0},
                    var defaultSpeed: Double = 5.0
                  ) extends ModuleConf {

  /**
    * singleton per worker, lost on shipping
    */
  def dronesInFleet: Set[Drone] = fleet.apply()

  // TODO: use reflection to automate
  override def importFrom(sparkConf: SparkConf): UAVConf.this.type = this.copy().asInstanceOf[this.type]
}