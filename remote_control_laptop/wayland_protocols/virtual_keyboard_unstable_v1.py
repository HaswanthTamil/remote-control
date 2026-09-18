# This file has been hand-written following the pywayland scanner output style.
#
# Source protocol: virtual-keyboard-unstable-v1.xml
#   Copyright (c) 2011 Intel Corporation
#   Copyright (c) 2012 Collabora, Ltd.
#   Copyright (c) 2015 Jonas Adahl
#   Licensed under the MIT Expat license.
#
# Mirrors the wlroots / Hyprland implementation (zwp_virtual_keyboard_v1).

from __future__ import annotations

from pywayland.protocol_core import Argument, ArgumentType, Global, Interface, Proxy, Resource

from .wayland import WlSeat, WlSeatProxy


class ZwpVirtualKeyboardV1(Interface):
    """Virtual keyboard

    The virtual keyboard provides an application with requests which
    emulate the behaviour of a physical keyboard.

    This interface can be used by clients on its own to provide raw input
    events, or it can accompany the input method protocol.
    """

    name = "zwp_virtual_keyboard_v1"
    version = 1


class ZwpVirtualKeyboardManagerV1(Interface):
    """Virtual keyboard manager

    A virtual keyboard manager allows an application to provide keyboard
    input events as if they came from a physical keyboard.
    """

    name = "zwp_virtual_keyboard_manager_v1"
    version = 1


class ZwpVirtualKeyboardV1Resource(Resource[ZwpVirtualKeyboardV1]):
    interface = ZwpVirtualKeyboardV1


class ZwpVirtualKeyboardManagerV1Resource(Resource[ZwpVirtualKeyboardManagerV1]):
    interface = ZwpVirtualKeyboardManagerV1


class ZwpVirtualKeyboardV1Proxy(Proxy[ZwpVirtualKeyboardV1]):
    interface = ZwpVirtualKeyboardV1

    @ZwpVirtualKeyboardV1.request(
        Argument(ArgumentType.Uint),
        Argument(ArgumentType.FileDescriptor),
        Argument(ArgumentType.Uint),
    )
    def keymap(self, format: int, fd: int, size: int) -> None:
        """Keymap

        Provide a file descriptor to the compositor which can be mapped
        to provide a keyboard mapping description.

        Format carries a value from the keymap_format enumeration.

        :param format:
            keymap format
        :param fd:
            keymap file descriptor
        :param size:
            keymap size, in bytes
        """
        self._marshal(0, format, fd, size)

    @ZwpVirtualKeyboardV1.request(
        Argument(ArgumentType.Uint),
        Argument(ArgumentType.Uint),
        Argument(ArgumentType.Uint),
    )
    def key(self, time: int, key: int, state: int) -> None:
        """Key event

        A key was pressed or released.

        The time argument is a timestamp with millisecond granularity,
        with an undefined base. All requests regarding a single object
        must share the same clock.

        Keymap must be set before issuing this request.

        State carries a value from the key_state enumeration.

        :param time:
            timestamp with millisecond granularity
        :param key:
            key that produced the event
        :param state:
            physical state of the key
        """
        self._marshal(1, time, key, state)

    @ZwpVirtualKeyboardV1.request(
        Argument(ArgumentType.Uint),
        Argument(ArgumentType.Uint),
        Argument(ArgumentType.Uint),
        Argument(ArgumentType.Uint),
    )
    def modifiers(
        self, mods_depressed: int, mods_latched: int, mods_locked: int, group: int
    ) -> None:
        """Modifier and group state

        Notifies the compositor that the modifier and/or group state has
        changed, and it should update state.

        The client should use wl_keyboard.modifiers event to synchronize
        its internal state with seat state.

        Keymap must be set before issuing this request.

        :param mods_depressed:
            depressed modifiers
        :param mods_latched:
            latched modifiers
        :param mods_locked:
            locked modifiers
        :param group:
            keyboard layout
        """
        self._marshal(2, mods_depressed, mods_latched, mods_locked, group)

    @ZwpVirtualKeyboardV1.request()
    def destroy(self) -> None:
        """Destroy the virtual keyboard object

        Destroy the virtual keyboard object.
        """
        self._marshal(3)
        self._destroy()


class ZwpVirtualKeyboardManagerV1Proxy(Proxy[ZwpVirtualKeyboardManagerV1]):
    interface = ZwpVirtualKeyboardManagerV1

    @ZwpVirtualKeyboardManagerV1.request(
        Argument(ArgumentType.Object, interface=WlSeat),
        Argument(ArgumentType.NewId, interface=ZwpVirtualKeyboardV1),
    )
    def create_virtual_keyboard(self, seat: WlSeatProxy) -> ZwpVirtualKeyboardV1Proxy:
        """Create a new virtual keyboard

        Creates a new virtual keyboard associated with a seat.

        If the compositor enables a keyboard to perform arbitrary
        actions, it should present an error when an untrusted client
        requests a new keyboard.

        :param seat:
            the seat that the keyboard should be associated with
        :returns:
            a virtual keyboard interface
        """
        id = self._marshal_constructor(0, ZwpVirtualKeyboardV1, seat)
        return id


class ZwpVirtualKeyboardV1Global(Global[ZwpVirtualKeyboardV1]):
    interface = ZwpVirtualKeyboardV1


class ZwpVirtualKeyboardManagerV1Global(Global[ZwpVirtualKeyboardManagerV1]):
    interface = ZwpVirtualKeyboardManagerV1


ZwpVirtualKeyboardV1._gen_c()
ZwpVirtualKeyboardV1.proxy_class = ZwpVirtualKeyboardV1Proxy
ZwpVirtualKeyboardV1.resource_class = ZwpVirtualKeyboardV1Resource
ZwpVirtualKeyboardV1.global_class = ZwpVirtualKeyboardV1Global


ZwpVirtualKeyboardManagerV1._gen_c()
ZwpVirtualKeyboardManagerV1.proxy_class = ZwpVirtualKeyboardManagerV1Proxy
ZwpVirtualKeyboardManagerV1.resource_class = ZwpVirtualKeyboardManagerV1Resource
ZwpVirtualKeyboardManagerV1.global_class = ZwpVirtualKeyboardManagerV1Global