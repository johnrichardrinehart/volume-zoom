"""Emit a uinput JSON script: register a key device, then press keys.
usage: uinput-keys.py SPEC... | adb shell uinput -
SPEC = up|down (80 ms tap) | up:MS|down:MS (held MS) | wait:MS
"""
import json, sys
out = [{"id": 1, "command": "register", "name": "VZ test keys", "vid": 6353, "pid": 1, "bus": "usb",
        "configuration": [{"type": "UI_SET_EVBIT", "data": ["EV_KEY"]},
                          {"type": "UI_SET_KEYBIT", "data": ["KEY_VOLUMEDOWN", "KEY_VOLUMEUP"]}]},
       {"id": 1, "command": "delay", "duration": 1500}]
for spec in sys.argv[1:]:
    if spec.startswith("wait:"):
        out.append({"id": 1, "command": "delay", "duration": int(spec[5:])}); continue
    name, _, ms = spec.partition(":")
    k = {"up": "KEY_VOLUMEUP", "down": "KEY_VOLUMEDOWN"}[name]
    out += [{"id": 1, "command": "inject", "events": ["EV_KEY", k, 1, "EV_SYN", "SYN_REPORT", 0]},
            {"id": 1, "command": "delay", "duration": int(ms or 80)},
            {"id": 1, "command": "inject", "events": ["EV_KEY", k, 0, "EV_SYN", "SYN_REPORT", 0]},
            {"id": 1, "command": "delay", "duration": 100}]
out.append({"id": 1, "command": "delay", "duration": 500})
print("\n".join(json.dumps(o) for o in out))
