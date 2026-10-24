"""Runs one servoshell for sitecheck and makes sure it never outlives sitecheck.

servo.py starts this script (``python -I -S supervisor.py PROFILE COMMAND...``) as the
leader of a new process group, with a pipe on stdin that only sitecheck holds open.
The script starts COMMAND (servoshell) in the same group and waits for it:

- When servoshell exits, the script exits with servoshell's status (or dies by its
  signal), so sitecheck sees the exit as if it had started servoshell itself.
- When the pipe reaches end of file, sitecheck is gone, however it died: even a
  SIGKILL closes its file descriptors. The script then kills servoshell, removes the
  temporary profile PROFILE (it may hold the visited page's cookies) and kills
  anything left in the group, itself included.

sitecheck's own cleanup (stop(), the signal handlers) kills the whole group as
before; this script only covers the deaths no handler sees. It uses the standard
library only, since it runs isolated from sitecheck's environment.
"""

from __future__ import annotations

import contextlib
import os
import shutil
import signal
import subprocess
import sys
import threading

# Only a directory sitecheck created for one servoshell is ever removed.
_PROFILE_PREFIX = "sitecheck-servo-"
_READ_CHUNK_BYTES = 4096
_EXIT_CANNOT_START = 127
_SIGNAL_EXIT_BASE = 128


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print("usage: supervisor.py PROFILE COMMAND [ARGUMENT...]", file=sys.stderr)
        return _EXIT_CANNOT_START
    profile, command = argv[0], argv[1:]
    try:
        child = subprocess.Popen(command, stdin=subprocess.DEVNULL)
    except OSError as error:
        print(f"sitecheck supervisor: cannot start {command[0]}: {error.strerror}", file=sys.stderr)
        return _EXIT_CANNOT_START

    parent_gone = threading.Event()
    watcher = threading.Thread(
        target=_watch_parent, args=(child, profile, parent_gone), daemon=True
    )
    watcher.start()
    returncode = child.wait()
    if parent_gone.is_set():
        # The watcher killed servoshell; it removes the profile and then kills the
        # group, this process included.
        watcher.join()
    if returncode >= 0:
        return returncode
    # Die by the same signal, so sitecheck reads the same status as servoshell's.
    # SIGKILL and SIGSTOP have no handler to reset.
    with contextlib.suppress(OSError, ValueError):
        signal.signal(-returncode, signal.SIG_DFL)
    os.kill(os.getpid(), -returncode)
    return _SIGNAL_EXIT_BASE - returncode  # Only if the signal did not end the process.


def _watch_parent(
    child: subprocess.Popen[bytes], profile: str, parent_gone: threading.Event
) -> None:
    """Block until sitecheck closes the pipe, then end everything this script started."""
    try:
        while os.read(sys.stdin.fileno(), _READ_CHUNK_BYTES):
            pass
    except OSError:
        pass  # A broken pipe means the same: sitecheck is gone.

    parent_gone.set()
    with contextlib.suppress(OSError):
        child.kill()  # The main thread is in child.wait() and reaps it.
    if os.path.basename(os.path.normpath(profile)).startswith(_PROFILE_PREFIX):
        shutil.rmtree(profile, ignore_errors=True)
    os.killpg(os.getpgrp(), signal.SIGKILL)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
