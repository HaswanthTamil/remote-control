# Remote control for laptop from phone

This repo provides a small relay server and clients to run commands on a laptop from a phone.

Quick start (laptop agent)

1. Copy `remote_control_laptop/config.example.py` to `remote_control_laptop/config.py` and set `SERVER_URL` and `DEVICE_TOKEN`.
2. Run the laptop agent:

```bash
python3 remote_control_laptop/agent.py
```

The laptop agent now runs a persistent `bash` shell in a PTY so that `cd`, `export`, and other shell state persist between commands sent from the phone.
