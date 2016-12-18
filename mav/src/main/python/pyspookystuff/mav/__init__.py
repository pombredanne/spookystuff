from __future__ import print_function

import logging

import time
from dronekit import LocationGlobal, LocationGlobalRelative, LocationLocal, APIException
from dronekit import Vehicle, VehicleMode

from pyspookystuff.mav import Const
from pyspookystuff.mav.utils import retry


class MAVException(Exception):
    pass


def stdError(dist=10000.0, maxError = 1.0):
    error = min(dist * 0.05, maxError)
    return error


class VehicleFunctions(object):

    def __init__(self, vehicle):
        # type: (Vehicle) -> None
        self.vehicle = vehicle
        self.localOrigin = None
        self._homeLocation = None

    # all the following are blocking API
    @retry(Const.armRetries)
    def assureClearanceAltitude(self, minAlt, maxAlt=121.92, error=None): # max altitute capped to 400 ftp
        # type: (float, float) -> None
        if not error:
            error = stdError(minAlt)

        alt = self.vehicle.location.global_relative_frame.alt
        if (minAlt - alt) <= error:
            logging.info("already airborne")
        else:
            self.armAndLift(minAlt, maxAlt, error)

    def armAndLift(self, minAlt, maxAlt, error):
        # type: (float, float) -> None
        """
        from http://python.dronekit.io/develop/best_practice.html
        Launch sequence
        Generally you should use the standard launch sequence described in Taking Off:
        Poll on Vehicle.is_armable until the vehicle is ready to arm.
        Set the Vehicle.mode to GUIDED
        Set Vehicle.armed to True and poll on the same attribute until the vehicle is armed.
        Call Vehicle.simple_takeoff with a target altitude.
        Poll on the altitude and allow the code to continue only when it is reached.
        The approach ensures that commands are only sent to the vehicle when it is able to act on them
        (e.g. we know Vehicle.is_armable is True before trying to arm, we know Vehicle.armed is True before we take off).
        It also makes debugging takeoff problems a lot easier.
        """
        # Wait until the vehicle reaches a safe height before
        # processing the goto (otherwise the command after
        # Vehicle.simple_takeoff will execute immediately).

        def arm(vehicle):
            # type: (Vehicle) -> None
            # Don't let the user try to fly when autopilot is booting

            def notArmable():
                return not vehicle.is_armable
            utils.waitFor(notArmable, 60)

            # Copter should arm in GUIDED mode

            vehicle.mode = VehicleMode("GUIDED")

            def isGuided():
                return vehicle.mode.name == 'GUIDED'
            utils.waitFor(isGuided, 60)

            # Arm copter.
            vehicle.armed = True

            def isArmed():
                return vehicle.armed and vehicle.mode.name == 'GUIDED'
            utils.waitFor(isArmed, 60)

        def armAndTakeOff(vehicle):
            previousAlt = None
            while True:
                if vehicle.is_armable:
                    arm(vehicle)

                alt = vehicle.location.global_relative_frame.alt
                if alt <= 1:
                    print("taking off from the ground ... ")
                    vehicle.simple_takeoff(minAlt)
                # Test for altitude just below target, in case of undershoot.
                elif (minAlt - alt) <= error:
                    print("Reached target altitude")
                    break
                elif previousAlt:
                    if alt <= previousAlt:
                        print("already airborne")
                        break

                print("Taking off: altitude =", alt, "\tminimumAltitude =", minAlt)
                previousAlt = alt
                self.failOnTimeout()
                time.sleep(1)

        alt = self.vehicle.location.global_relative_frame.alt
        if self.vehicle.is_armable or alt <= 1:
            armAndTakeOff(self.vehicle)

        if (minAlt - alt) > error:
            self.move(LocationGlobalRelative(None, None, minAlt))
        elif (alt - maxAlt) > error:
            self.move(LocationGlobalRelative(None, None, maxAlt))
        else:
            print("Vehicle is airborne, altitude =", self.vehicle.location.global_relative_frame.alt)
            return

    def getLocalOrigin(self):
        # type: () -> LocationGlobal
        if not self.localOrigin:
            self.localOrigin = self.homeLocation
        return self.localOrigin

    @property
    @retry()
    def homeLocation(self):
        # type: () -> LocationGlobal
        """
        slow and may retry several times, use with caution
        """
        if not self._homeLocation:
            if not self.vehicle.home_location:
                self.vehicle.commands.download()
                self.vehicle.commands.wait_ready()
            self._homeLocation = self.vehicle.home_location
        return self._homeLocation

    def move(self, targetLocation):
        # type: (LocationGlobal) -> None
        # type: (Vehicle, LocationGlobalRelative) -> None
        # type: (Vehicle, LocationLocal) -> None
        """
        does a lot of things:
        block until reaching target location
        if mode!=GUIDED will sleep until mode==GUIDED or reaching target
        will issue simple_goto immediately once mode becomes GUIDED, will issue repeatedly if distance is not closing

        :param vehicle: a vessel of spirit ...
        :param targetLocation: can be LocationGlobal or LocationGlobalRelative.
            if any member==None will copy current vehicle's location into the missing part, feel free to set altitude along
        """

        effectiveTL = targetLocation # type: Union[LocationGlobal,LocationGlobalRelative]

        if isinstance(targetLocation, LocationLocal):
            north = targetLocation.north
            east = targetLocation.east
            down = targetLocation.down
            hl = self.getLocalOrigin()
            effectiveTL = utils.get_location_metres(hl, north, east)
            effectiveTL.alt = hl.alt - down

        def currentL():
            # type: () -> Union[LocationGlobal,LocationGlobalRelative]
            if isinstance(targetLocation, LocationGlobal):
                return self.vehicle.location.global_frame
            elif isinstance(targetLocation, LocationGlobalRelative):
                return self.vehicle.location.global_relative_frame
            elif isinstance(targetLocation, LocationLocal):
                return self.vehicle.location.global_frame
            else:
                raise NotImplementedError("Only support Dronekit Locations (Global/GlobalRelative/Local)")

        if not effectiveTL.lat:
            effectiveTL.lat = currentL().lat
        if not effectiveTL.lon:
            effectiveTL.lon = currentL().lon
        if not effectiveTL.alt:
            effectiveTL.alt = currentL().alt

        oldDistance = None

        # self.getHomeLocation
        while True: # Stop action if we are no longer in guided mode.
            distance, hori, vert=utils.airDistance(currentL(), effectiveTL)
            print(
                "Moving ... \tremaining distance:", str(distance) + "m",
                "\thorizontal:", str(hori) + "m",
                "\tvertical:", str(vert) + "m"
            )

            if self.vehicle.mode.name=="GUIDED":
                if oldDistance <= distance:
                    # TODO: calculation of closest distance can be more refined
                    if oldDistance is not None and oldDistance <= stdError(maxError= 2):
                        print("Reached target")
                        break
                    else:
                        self.simple_goto(effectiveTL)
                        print("Engaging thruster")
                oldDistance = distance
            else:
                print("Control has been relinquished to manual")
                oldDistance = None

            self.failOnTimeout()
            time.sleep(1)

    def reconnect(self):
        pass

    @retry(2)
    def simple_goto(self, effectiveTL, airspeed=None, groundspeed=None):
        # type: (LocationGlobal) -> None
        """
        vanilla simple_goto() may timeout, adding retry
        """
        try:
            self.vehicle.simple_goto(effectiveTL, airspeed, groundspeed)
        except APIException as e:
            self.reconnect()

    def failOnTimeout(self):
        assert(self.vehicle.last_heartbeat < 30)



