"""Download the hand-tracking files TV Hub bundles (Google MediaPipe Tasks Vision, Apache 2.0).

Run once before building TV Hub:   python tools/fetch_mediapipe.py

They are about 22 MB, so they are not stored in git. Each file is pinned and checked against a SHA-256, and the
files end up in hub-app/app/src/main/assets/mp/, which the phone page and the TV-camera page load from the TV
itself, so gestures work without the internet.
"""
import hashlib
import sys
import urllib.request
from pathlib import Path

VERSION = "1.2.0"
CDN = f"https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@{VERSION}/"
MODEL = "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task"
OUT = Path(__file__).resolve().parent.parent / "hub-app" / "app" / "src" / "main" / "assets" / "mp"

FILES = [
    # (destination, url, sha256)
    ("vision_bundle.mjs", CDN + "vision_bundle.mjs",
     "870da5cfc43bfcf2a8b0f14e8152dae8c7e701722e1191b0e592b94b81b4127a"),
    ("wasm/vision_wasm_internal.js", CDN + "wasm/vision_wasm_internal.js",
     "85c1a342be70427987f900969a5f26e17557351111364e6585e97fb03e93e108"),
    ("wasm/vision_wasm_internal.wasm", CDN + "wasm/vision_wasm_internal.wasm",
     "bcc13df22d3d69ec8cbe8e280989356d844ae00f4523eb82072d2484f44134dc"),
    ("hand_landmarker.task", MODEL,
     "fbc2a30080c3c557093b5ddfc334698132eb341044ccee322ccf8bcf3607cde1"),
]
TIMEOUT_S = 120


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main() -> int:
    for name, url, want in FILES:
        dest = OUT / name
        if dest.is_file() and sha256(dest) == want:
            print(f"ok      {name}")
            continue
        dest.parent.mkdir(parents=True, exist_ok=True)
        print(f"fetch   {name}")
        with urllib.request.urlopen(url, timeout=TIMEOUT_S) as r, dest.open("wb") as f:
            f.write(r.read())
        got = sha256(dest)
        if got != want:
            dest.unlink()
            print(f"FAILED  {name}: checksum {got} is not the expected {want}", file=sys.stderr)
            return 1
    print("done")
    return 0


if __name__ == "__main__":
    sys.exit(main())
