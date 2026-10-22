#!/usr/bin/env python3
"""Turns a build's stream log into a replay script (spec §16, §17).

A Claude build leaves logs/aibuilder/<name>.stream.jsonl: Claude Code's raw stream, with every tool call's full
arguments (the build log next to it shortens long arrays). This writes the calls out as the
{"calls": [{"tool": ..., "args": ...}]} script ScriptedBackend replays through the real tools, the same format as
src/main/resources/aibuilder/scripts/demo_house.json, for a test that rebuilds a real build offline.

    python3 tools/stream_to_script.py run/logs/aibuilder/<name>.stream.jsonl > src/test/resources/<name>.json
"""
import json
import sys

PREFIX = "mcp__aibuilder__"


def calls(lines):
    seen = set()
    for line in lines:
        try:
            event = json.loads(line)
        except ValueError:
            continue
        if not isinstance(event, dict) or event.get("type") != "assistant":
            continue
        for block in (event.get("message") or {}).get("content") or []:
            if not isinstance(block, dict) or block.get("type") != "tool_use":
                continue
            name = block.get("name", "")
            # Claude Code can repeat a message's blocks across lines; each call once.
            if not name.startswith(PREFIX) or block.get("id") in seen:
                continue
            seen.add(block.get("id"))
            yield {"tool": name[len(PREFIX):], "args": block.get("input") or {}}


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    with open(sys.argv[1], encoding="utf-8") as stream:
        script = {"calls": list(calls(stream))}
    json.dump(script, sys.stdout, indent="\t", ensure_ascii=False)
    sys.stdout.write("\n")


if __name__ == "__main__":
    main()
