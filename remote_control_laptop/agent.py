import json
import subprocess
import threading
import uuid

import websocket

from config import *


if not DEVICE_TOKEN:
    raise RuntimeError("REMOTE_CONTROL_TOKEN is not set")


def send(ws, message):
    ws.send(json.dumps(message))


def stream_output(ws, process, stream, message_type, command_id):
    # Read process output line-by-line and forward it to the phone.
    try:
        for line in iter(stream.readline, ""):
            if not line:
                break

            send(ws, {
                "type": message_type,
                "id": command_id,
                "data": line
            })
    finally:
        stream.close()


def execute_command(ws, message):
    command_id = message.get("id") or str(uuid.uuid4())
    command = message.get("command")

    if not isinstance(command, str) or not command.strip():
        send(ws, {
            "type": "error",
            "id": command_id,
            "message": "Invalid command"
        })
        return

    print(f"[command] {command}")

    send(ws, {
        "type": "ack",
        "id": command_id
    })

    try:
        process = subprocess.Popen(
            command,
            shell=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            bufsize=1
        )

        stdout_thread = threading.Thread(
            target=stream_output,
            args=(ws, process, process.stdout, "output", command_id),
            daemon=True
        )

        stderr_thread = threading.Thread(
            target=stream_output,
            args=(ws, process, process.stderr, "stderr", command_id),
            daemon=True
        )

        stdout_thread.start()
        stderr_thread.start()

        exit_code = process.wait()

        stdout_thread.join()
        stderr_thread.join()

        send(ws, {
            "type": "exit",
            "id": command_id,
            "code": exit_code
        })

        print(f"[exit] {exit_code}")

    except Exception as error:
        print(f"[error] {error}")

        send(ws, {
            "type": "error",
            "id": command_id,
            "message": str(error)
        })


def on_open(ws):
    print("[connected]")

    send(ws, {
        "type": "register",
        "device": "laptop",
        "token": DEVICE_TOKEN
    })


def on_message(ws, raw_message):
    try:
        message = json.loads(raw_message)
    except json.JSONDecodeError:
        print("[error] Received invalid JSON")
        return

    message_type = message.get("type")

    if message_type == "registered":
        print("[registered as laptop]")
        return

    if message_type == "command":
        # Run each command in its own thread so the WebSocket
        # remains responsive while the process is running.
        threading.Thread(
            target=execute_command,
            args=(ws, message),
            daemon=True
        ).start()

        return

    if message_type == "error":
        print(f"[server error] {message.get('message')}")
        return

    print(f"[unknown message] {message}")


def on_error(ws, error):
    print(f"[websocket error] {error}")


def on_close(ws, close_status_code, close_message):
    print(
        f"[disconnected] "
        f"code={close_status_code} message={close_message}"
    )


def main():
    websocket.enableTrace(False)

    ws = websocket.WebSocketApp(
        SERVER_URL,
        on_open=on_open,
        on_message=on_message,
        on_error=on_error,
        on_close=on_close
    )

    print(f"[connecting] {SERVER_URL}")

    ws.run_forever()


if __name__ == "__main__":
    main()
