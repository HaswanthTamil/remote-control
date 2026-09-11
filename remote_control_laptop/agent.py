import json
import os
import pty
import select
import threading
import time
import uuid
import signal
import fcntl
import errno
import re

import websocket

from config import *


if not DEVICE_TOKEN:
    raise RuntimeError("REMOTE_CONTROL_TOKEN is not set")


# Globals
_master_fd = None
_child_pid = None
_reader_thread = None
_ws = None
_ws_lock = threading.Lock()
_send_lock = threading.Lock()
_cmd_lock = threading.Lock()
_current_command_id = None
_pending_exits = {}

# Sentinel prefix (unique per agent run)
_SENTINEL = f"__CMD_DONE__{uuid.uuid4().hex}__"


def _set_nonblocking(fd):
    flags = fcntl.fcntl(fd, fcntl.F_GETFL)
    fcntl.fcntl(fd, fcntl.F_SETFL, flags | os.O_NONBLOCK)


def spawn_persistent_shell():
    global _master_fd, _child_pid

    pid, master_fd = pty.fork()
    if pid == 0:
        # Child: become an interactive bash
        os.execlp('bash', 'bash', '-i')

    # Parent
    _child_pid = pid
    _master_fd = master_fd
    try:
        _set_nonblocking(_master_fd)
    except Exception:
        pass

    # Disable terminal echo for the life of the shell so command lines (and
    # the sentinel inside them) are never echoed back into the output stream.
    try:
        os.write(master_fd, b"stty -echo\n")
    except Exception:
        pass


def send_ws(message):
    global _ws
    s = json.dumps(message)
    with _send_lock:
        try:
            if _ws and getattr(_ws, 'sock', None) and getattr(_ws.sock, 'connected', False):
                _ws.send(s)
        except Exception:
            # ignore send errors (will be retried on reconnect)
            return


def _reader_loop():
    global _master_fd, _current_command_id, _pending_exits
    buf = ''
    # precompile regexes for stripping control sequences
    osc_re = re.compile(r'\x1b\][^\x1b]*(?:\x1b\\|\x07)', re.DOTALL)
    csi_re = re.compile(r'\x1b\[[0-9;?]*[ -/]*[@-~]')
    c0_re = re.compile(r'[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]')

    def _clean_text(s: str) -> str:
        # remove OSC sequences (ESC ] ... ESC\ or BEL), CSI sequences, and other C0 controls
        s = osc_re.sub('', s)
        s = csi_re.sub('', s)
        s = c0_re.sub('', s)
        # normalize line endings so phone renders one line per row
        s = s.replace('\r\n', '\n').replace('\r', '\n')
        return s
    while True:
        if _master_fd is None:
            time.sleep(0.1)
            continue

        try:
            r, _, _ = select.select([_master_fd], [], [], 0.1)
            if not r:
                continue

            data = os.read(_master_fd, 4096)
            if not data:
                time.sleep(0.1)
                continue

            chunk = data.decode('utf-8', errors='replace')
            buf += chunk

            # Process any complete sentinel markers.
            while True:
                idx = buf.find(_SENTINEL)
                if idx == -1:
                    # No sentinel yet; forward all and break
                    
                    if buf:
                        cid = None
                        with _cmd_lock:
                            cid = _current_command_id

                        # Only forward output when associated with a running command
                        if cid is not None:
                            send_ws({
                                'type': 'output',
                                'id': cid,
                                'data': _clean_text(buf)
                            })
                        buf = ''
                    break

                # Found sentinel; split
                before = buf[:idx]
                rest = buf[idx + len(_SENTINEL):]

                # forward 'before' as output (may be empty)
                if before:
                    cid = None
                    with _cmd_lock:
                        cid = _current_command_id

                    cleaned_before = _clean_text(before)

                    if cleaned_before and cid is not None:
                        send_ws({
                            'type': 'output',
                            'id': cid,
                            'data': cleaned_before
                        })

                # Now parse the sentinel line which should be like: <command_id>:<exit>\n
                nl = rest.find('\n')
                if nl == -1:
                    # sentinel not complete yet; wait for more
                    buf = _SENTINEL + rest
                    break

                line = rest[:nl].strip()
                buf = rest[nl+1:]

                # line expected: <command_id>:<exit_code>
                try:
                    cmd_id, code_str = line.split(':', 1)
                    code = int(code_str)
                except Exception:
                    # malformed sentinel; ignore
                    continue

                # Send exit message for that command id
                send_ws({
                    'type': 'exit',
                    'id': cmd_id,
                    'code': code
                })

                # If the finished command is the current_command, clear it
                with _cmd_lock:
                    if _current_command_id == cmd_id:
                        _current_command_id = None

                # continue processing remaining buffer
                continue

        except OSError as e:
            if e.errno in (errno.EIO, errno.EBADF):
                # PTY closed
                break
            time.sleep(0.1)
        except Exception:
            time.sleep(0.1)


def _write_to_pty(data: bytes):
    global _master_fd
    if _master_fd is None:
        raise RuntimeError('PTY not spawned')

    os.write(_master_fd, data)


def _handle_command(message):
    global _current_command_id

    command_id = message.get('id') or str(uuid.uuid4())
    command = message.get('command')

    if not isinstance(command, str) or not command.strip():
        send_ws({
            'type': 'error',
            'id': command_id,
            'message': 'Invalid command'
        })
        return

    print(f"[command] {command}")

    # acknowledge immediately
    send_ws({
        'type': 'ack',
        'id': command_id
    })

    # Terminal echo was disabled at shell spawn, so the command is not echoed.
    # Run the command, then print a sentinel carrying its id and exit code on
    # its own line. $? must stay out of single quotes so bash expands it.
    wrapped_cmd = f"{command}; printf \"\\n{_SENTINEL}{command_id}:$?\\n\"\n"

    with _cmd_lock:
        _current_command_id = command_id

    try:
        _write_to_pty(wrapped_cmd.encode('utf-8'))
    except Exception as e:
        send_ws({
            'type': 'error',
            'id': command_id,
            'message': str(e)
        })


def _handle_signal(message):
    sig = message.get('signal')
    # map string names to actions
    if not sig:
        return

    if sig.upper() == 'SIGINT':
        # send Ctrl-C to the pty
        try:
            _write_to_pty(b"\x03")
        except Exception:
            pass
        # also try sending to the child process
        try:
            if _child_pid:
                os.kill(_child_pid, signal.SIGINT)
        except Exception:
            pass


def on_open(ws):
    global _ws
    with _ws_lock:
        _ws = ws

    print('[connected]')
    send_ws({
        'type': 'register',
        'device': 'laptop',
        'token': DEVICE_TOKEN
    })


def on_message(ws, raw_message):
    try:
        message = json.loads(raw_message)
    except json.JSONDecodeError:
        print('[error] Received invalid JSON')
        return

    message_type = message.get('type')

    if message_type == 'registered':
        print('[registered as laptop]')
        return

    if message_type == 'command':
        threading.Thread(target=_handle_command, args=(message,), daemon=True).start()
        return

    if message_type == 'signal':
        threading.Thread(target=_handle_signal, args=(message,), daemon=True).start()
        return

    if message_type == 'error':
        print(f"[server error] {message.get('message')}")
        return

    print(f"[unknown message] {message}")


def on_error(ws, error):
    print(f"[websocket error] {error}")


def on_close(ws, close_status_code, close_message):
    global _ws
    with _ws_lock:
        _ws = None

    print(f"[disconnected] code={close_status_code} message={close_message}")


def connect_loop():
    websocket.enableTrace(False)

    backoff = 1.0
    while True:
        try:
            ws_app = websocket.WebSocketApp(
                SERVER_URL,
                on_open=on_open,
                on_message=lambda ws, msg: on_message(ws, msg),
                on_error=on_error,
                on_close=on_close,
            )

            print(f"[connecting] {SERVER_URL}")
            # run_forever will block until disconnected
            ws_app.run_forever()
        except Exception as e:
            print(f"[connect error] {e}")

        # backoff before reconnect
        time.sleep(backoff)
        backoff = min(backoff * 2, 30)


def main():
    # spawn PTY once and keep it across reconnects
    spawn_persistent_shell()

    # start reader thread
    global _reader_thread
    _reader_thread = threading.Thread(target=_reader_loop, daemon=True)
    _reader_thread.start()

    # start websocket connect loop (blocks)
    connect_loop()


if __name__ == '__main__':
    main()
