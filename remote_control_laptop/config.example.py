SERVER_URL = "ws://localhost:3000"

# One-time pairing token. The relay only accepts a brand-new public key if
# this token is presented; it is never sent again once this laptop is paired.
# Leave empty ("") after the relay has registered this laptop.
PAIR_TOKEN = ""