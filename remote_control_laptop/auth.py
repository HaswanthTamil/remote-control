"""Ed25519 identity management for the laptop agent.

Private key stays on this machine (0600), never leaves it.
device_id is sha256(raw public key).hexdigest() - computed identically
by Node (relay) and the browser (phone), so it doubles as the key's
fingerprint for pairing verification.
"""

import hashlib
import os

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

import dashboard

_KEYS_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "keys")
PRIV_PATH = os.path.join(_KEYS_DIR, "id_ed25519")
PUB_PATH = os.path.join(_KEYS_DIR, "id_ed25519.pub")


def _marker_path(server_url):
    """Pairing is per relay: each relay keeps its own keystore, so a device
    must present PAIR_TOKEN once for every relay it connects to."""
    digest = hashlib.sha1(server_url.encode("utf-8")).hexdigest()[:16]
    return os.path.join(_KEYS_DIR, f"paired_{digest}")

_PRIVATE = None


def _ensure_dirs():
    os.makedirs(_KEYS_DIR, mode=0o700, exist_ok=True)


def _private_key():
    global _PRIVATE

    if _PRIVATE is not None:
        return _PRIVATE

    _ensure_dirs()

    if os.path.exists(PRIV_PATH):
        with open(PRIV_PATH, "rb") as f:
            _PRIVATE = serialization.load_pem_private_key(f.read(), password=None)
        return _PRIVATE

    _PRIVATE = Ed25519PrivateKey.generate()

    priv_bytes = _PRIVATE.private_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PrivateFormat.PKCS8,
        encryption_algorithm=serialization.NoEncryption(),
    )
    pub_hex = _public_key_hex(_PRIVATE)

    fd = os.open(PRIV_PATH, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "wb") as f:
        f.write(priv_bytes)
    os.chmod(PRIV_PATH, 0o600)

    with open(PUB_PATH, "w") as f:
        f.write(pub_hex + "\n")

    dashboard.log("[auth] generated new Ed25519 keypair")
    dashboard.log(f"[auth] public key: {pub_hex}")
    dashboard.log(f"[auth] device id:  {device_id()}")

    return _PRIVATE


def _public_key_hex(key=None):
    key = key or _private_key()
    raw = key.public_key().public_bytes(
        encoding=serialization.Encoding.Raw,
        format=serialization.PublicFormat.Raw,
    )
    return raw.hex()


def public_key_hex():
    return _public_key_hex()


def device_id():
    raw = bytes.fromhex(public_key_hex())
    return hashlib.sha256(raw).hexdigest()


def sign(message):
    """Sign an arbitrary string (the auth nonce payload) with Ed25519."""
    return _private_key().sign(message.encode("utf-8")).hex()


def mark_paired(server_url):
    with open(_marker_path(server_url), "w") as f:
        f.write("1\n")


def is_paired(server_url):
    return os.path.exists(_marker_path(server_url))


def clear_paired():
    for name in os.listdir(_KEYS_DIR):
        if name.startswith("paired_"):
            os.remove(os.path.join(_KEYS_DIR, name))