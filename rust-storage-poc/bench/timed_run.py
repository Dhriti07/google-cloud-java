#!/usr/bin/env python3
"""Runs a command and records its wall time, CPU (user+sys) and max RSS.

Usage: timed_run.py <base> <cmd> [args...]
Writes <base>.stdout, <base>.stderr and <base>.proc.json. Exits with the command's exit code.
Used for every implementation, so process-level CPU/RSS is measured the same way for all of them.

stderr is streamed through a pipe and only the last TIMED_STDERR_TAIL_BYTES (default 1 MiB) are
kept on disk, so a chatty program can't fill a small VM disk. The program still writes all of its logs (same cost as usual); we just
don't store them. CPU/RSS come from wait4() on the child only, so reading the pipe here is not
charged to the measured process.
"""

import collections
import json
import os
import subprocess
import sys
import time

TAIL_BYTES = int(os.environ.get("TIMED_STDERR_TAIL_BYTES", str(1 << 20)))


def main() -> int:
    if len(sys.argv) < 3:
        sys.exit("usage: timed_run.py <base> <cmd> [args...]")
    base, cmd = sys.argv[1], sys.argv[2:]
    tail = collections.deque()
    tail_len = 0
    dropped = 0
    with open(base + ".stdout", "wb") as out:
        t0 = time.monotonic()
        proc = subprocess.Popen(cmd, stdout=out, stderr=subprocess.PIPE)
        while True:
            chunk = proc.stderr.read1(1 << 16)
            if not chunk:
                break
            tail.append(chunk)
            tail_len += len(chunk)
            while tail_len - len(tail[0]) >= TAIL_BYTES:
                old = tail.popleft()
                tail_len -= len(old)
                dropped += len(old)
        proc.stderr.close()
        _, status, ru = os.wait4(proc.pid, 0)
        wall = time.monotonic() - t0
    with open(base + ".stderr", "wb") as err:
        if dropped:
            err.write(f"[timed_run: {dropped} earlier stderr bytes not stored]\n".encode())
        for chunk in tail:
            err.write(chunk)
    code = os.waitstatus_to_exitcode(status)
    info = {
        "cmd": cmd,
        "exit_code": code,
        "wall_s": round(wall, 3),
        "user_s": round(ru.ru_utime, 3),
        "sys_s": round(ru.ru_stime, 3),
        "max_rss_kib": ru.ru_maxrss,
        "stderr_bytes_total": dropped + tail_len,
    }
    with open(base + ".proc.json", "w") as f:
        json.dump(info, f, indent=2)
    print(
        f"[timed] {os.path.basename(base)}: exit={code} wall={wall:.1f}s "
        f"cpu={ru.ru_utime + ru.ru_stime:.1f}s maxrss={ru.ru_maxrss // 1024}MiB",
        flush=True,
    )
    if code != 0:
        tail_text = b"".join(tail)[-2000:].decode("utf-8", "replace")
        print(f"[timed] stderr tail:\n{tail_text}", file=sys.stderr, flush=True)
    return code


if __name__ == "__main__":
    sys.exit(main())
