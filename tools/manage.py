#!/usr/bin/env python3
"""Call Sefirah's local management provider through an explicitly selected ADB transport."""
import argparse
import json
import re
import shlex
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--serial", "-s", required=True)
parser.add_argument("method", choices=["status", "start", "stop", "connect", "pair", "approve", "disconnect", "options"])
parser.add_argument("argument", nargs="?")
args = parser.parse_args()
command = ["content", "call", "--uri", "content://com.castle.sefirah.management", "--method", args.method]
if args.argument is not None:
    command.extend(["--arg", args.argument])
result = subprocess.run(["adb", "-s", args.serial, "shell", shlex.join(command)],
    check=True, capture_output=True, text=True, timeout=25)
match = re.search(r"Result: Bundle\[\{result=(.*)\}\]", result.stdout)
if not match:
    raise SystemExit(result.stdout + result.stderr)
response = json.loads(match.group(1))
print(json.dumps(response, ensure_ascii=False, indent=2))
if not response.get("ok"):
    raise SystemExit(1)
