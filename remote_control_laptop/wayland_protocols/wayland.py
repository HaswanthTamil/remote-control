# Shim so the vendored protocol modules can do `from .wayland import ...`
# exactly the way pywayland's own generated modules do it, but resolve to the
# core interfaces bundled with pywayland.
from pywayland.protocol.wayland import (  # noqa: F401
    WlOutput,
    WlOutputProxy,
    WlSeat,
    WlSeatProxy,
)