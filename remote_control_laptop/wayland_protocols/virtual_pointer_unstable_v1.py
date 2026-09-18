# This file has been hand-written following the pywayland scanner output style.
#
# Source protocol: wlr-virtual-pointer-unstable-v1.xml
#   Copyright (c) 2019      Simon Ser
#   Licensed under the MIT Expat license (see the wlroots repository).
#
# Mirrors the wlroots / Hyprland implementation (zwlr_virtual_pointer_v1,
# version 2, advertised by the compositor as zwlr_virtual_pointer_manager_v1).

from __future__ import annotations

from pywayland.protocol_core import Argument, ArgumentType, Global, Interface, Proxy, Resource

from .wayland import WlOutput, WlOutputProxy, WlSeat, WlSeatProxy


class ZwlrVirtualPointerV1(Interface):
    """Virtual pointer

    This protocol allows the creation of virtual pointers and provides
    input event injection for them. Each virtual pointer object is bound
    to a wl_pointer object for the duration of its existence.
    """

    name = "zwlr_virtual_pointer_v1"
    version = 2


class ZwlrVirtualPointerManagerV1(Interface):
    """Virtual pointer manager

    A virtual pointer manager allows an application to provide pointer
    input events as if they came from a physical device.
    """

    name = "zwlr_virtual_pointer_manager_v1"
    version = 2


class ZwlrVirtualPointerV1Resource(Resource[ZwlrVirtualPointerV1]):
    interface = ZwlrVirtualPointerV1


class ZwlrVirtualPointerManagerV1Resource(Resource[ZwlrVirtualPointerManagerV1]):
    interface = ZwlrVirtualPointerManagerV1


class ZwlrVirtualPointerV1Proxy(Proxy[ZwlrVirtualPointerV1]):
    interface = ZwlrVirtualPointerV1

    @ZwlrVirtualPointerV1.request(
        Argument(ArgumentType.Uint),
        Argument(ArgumentType.Fixed),
        Argument(ArgumentType.Fixed),
    )
    def motion(self, time: int, dx: float, dy: float) -> None:
        """Relative motion

        Motion is relative to the current cursor position.

        :param time:
            timestamp with millisecond granularity
        :param dx:
            displacement on the x-axis
        :param dy:
            displacement on the y-axis
        """
        self._marshal(0, time, dx, dy)

    @ZwlrVirtualPointerV1.request(
        Argument(ArgumentType.Uint),
        Argument(ArgumentType.Uint),
        Argument(ArgumentType.Uint),
        Argument(ArgumentType.Uint),
        Argument(ArgumentType.Uint),
    )
    def motion_absolute(
        self, time: int, x: int, y: int, x_extent: int, y_extent: int
    ) -> None:
        """Absolute motion

        The compositor ignores any motion requests while the pointer is
        relative to another output, if the output is passed in a request.

        The x/y are in output-local coordinates, if an output was
        provided in the request. Otherwise they are in the compositor
        main output coordinates.

        :param time:
            timestamp with millisecond granularity
        :param x:
            position on the x-axis
        :param y:
            position on the y-axis
        :param x_extent:
            extent of the x-axis
        :param y_extent:
            extent of the y-axis
        """
        self._marshal(1, time, x, y, x_extent, y_extent)

    @ZwlrVirtualPointerV1.request(
        Argument(ArgumentType.Uint),
        Argument(ArgumentType.Uint),
        Argument(ArgumentType.Uint),
    )
    def button(self, time: int, button: int, state: int) -> None:
        """Button event

        A button was pressed or released.

        Button carries a value from the wl_pointer.button_state
        enumeration.

        :param time:
            timestamp with millisecond granularity
        :param button:
            button that produced the event
        :param state:
            physical state of the button
        """
        self._marshal(2, time, button, state)

    @ZwlrVirtualPointerV1.request(
        Argument(ArgumentType.Uint),
        Argument(ArgumentType.Uint),
        Argument(ArgumentType.Fixed),
    )
    def axis(self, time: int, axis: int, value: float) -> None:
        """Axis event

        Axis carries a value from the wl_pointer.axis enumeration. The
        value is in the same units as wl_pointer.axis events.

        :param time:
            timestamp with millisecond granularity
        :param axis:
            axis type
        :param value:
            length of vector in touchpad coordinates
        """
        self._marshal(3, time, axis, value)

    @ZwlrVirtualPointerV1.request(Argument(ArgumentType.Uint))
    def frame(self, time: int) -> None:
        """Frame event

        Request for the compositor to process the batch of events since
        the last frame.

        :param time:
            timestamp with millisecond granularity
        """
        self._marshal(4, time)

    @ZwlrVirtualPointerV1.request(Argument(ArgumentType.Uint))
    def axis_source(self, axis_source: int) -> None:
        """Axis source

        Source of the axis event. Value is one of the wl_pointer.axis_source
        values.

        :param axis_source:
            source of the axis event
        """
        self._marshal(5, axis_source)

    @ZwlrVirtualPointerV1.request(
        Argument(ArgumentType.Uint),
        Argument(ArgumentType.Uint),
    )
    def axis_stop(self, time: int, axis: int) -> None:
        """Axis stop

        Notifies the compositor that the axis was not a continuous motion
        but a terminating event.

        :param time:
            timestamp with millisecond granularity
        :param axis:
            the axis that stopped with this event
        """
        self._marshal(6, time, axis)

    @ZwlrVirtualPointerV1.request(
        Argument(ArgumentType.Uint),
        Argument(ArgumentType.Uint),
        Argument(ArgumentType.Fixed),
        Argument(ArgumentType.Int),
    )
    def axis_discrete(self, time: int, axis: int, value: float, discrete: int) -> None:
        """Axis discrete

        Notifies the compositor of a discrete axis motion step.

        :param time:
            timestamp with millisecond granularity
        :param axis:
            axis type
        :param value:
            length of vector in touchpad coordinates
        :param discrete:
            number of steps
        """
        self._marshal(7, time, axis, value, discrete)

    @ZwlrVirtualPointerV1.request()
    def destroy(self) -> None:
        """Release the virtual pointer object

        Destroy the virtual pointer object.
        """
        self._marshal(8)
        self._destroy()


class ZwlrVirtualPointerManagerV1Proxy(Proxy[ZwlrVirtualPointerManagerV1]):
    interface = ZwlrVirtualPointerManagerV1

    @ZwlrVirtualPointerManagerV1.request(
        Argument(ArgumentType.Object, nullable=True, interface=WlSeat),
        Argument(ArgumentType.NewId, interface=ZwlrVirtualPointerV1),
    )
    def create_virtual_pointer(self, seat: WlSeatProxy) -> ZwlrVirtualPointerV1Proxy:
        """Create a new virtual pointer

        If the compositor enables a pointer to perform arbitrary actions,
        it should present an error when an untrusted client requests a
        new pointer.

        :param seat:
            the seat that the pointer should be associated with
        :returns:
            a virtual pointer interface
        """
        id = self._marshal_constructor(0, ZwlrVirtualPointerV1, seat)
        return id

    @ZwlrVirtualPointerManagerV1.request(
        Argument(ArgumentType.Object, nullable=True, interface=WlSeat),
        Argument(ArgumentType.Object, nullable=True, interface=WlOutput),
        Argument(ArgumentType.NewId, interface=ZwlrVirtualPointerV1),
    )
    def create_virtual_pointer_with_output(
        self, seat: WlSeatProxy, output: WlOutputProxy
    ) -> ZwlrVirtualPointerV1Proxy:
        """Create a new virtual pointer attached to an output

        Create a new virtual pointer that is associated with the given
        output.

        :param seat:
            the seat that the pointer should be associated with
        :param output:
            the output that the pointer should be associated with
        :returns:
            a virtual pointer interface
        """
        id = self._marshal_constructor(1, ZwlrVirtualPointerV1, seat, output)
        return id

    @ZwlrVirtualPointerManagerV1.request()
    def destroy(self) -> None:
        """Release the virtual pointer manager

        Destroy the manager object.
        """
        self._marshal(1)
        self._destroy()


class ZwlrVirtualPointerV1Global(Global[ZwlrVirtualPointerV1]):
    interface = ZwlrVirtualPointerV1


class ZwlrVirtualPointerManagerV1Global(Global[ZwlrVirtualPointerManagerV1]):
    interface = ZwlrVirtualPointerManagerV1


ZwlrVirtualPointerV1._gen_c()
ZwlrVirtualPointerV1.proxy_class = ZwlrVirtualPointerV1Proxy
ZwlrVirtualPointerV1.resource_class = ZwlrVirtualPointerV1Resource
ZwlrVirtualPointerV1.global_class = ZwlrVirtualPointerV1Global


ZwlrVirtualPointerManagerV1._gen_c()
ZwlrVirtualPointerManagerV1.proxy_class = ZwlrVirtualPointerManagerV1Proxy
ZwlrVirtualPointerManagerV1.resource_class = ZwlrVirtualPointerManagerV1Resource
ZwlrVirtualPointerManagerV1.global_class = ZwlrVirtualPointerManagerV1Global