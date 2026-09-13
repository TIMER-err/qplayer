#!/usr/bin/env python3
"""Run the actual desktop graphics backend without the player or a user profile."""
import argparse
import csv
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("backend", choices=("gl", "vulkan", "rawgl"))
    parser.add_argument("--frames", type=int, default=360)
    parser.add_argument("--trace", type=Path, default=Path("desktop-host/target/frame-probe"))
    parser.add_argument("--readback", action="store_true", help="Verify the GL marker before swap")
    parser.add_argument("--skip-build", action="store_true", help="Use existing test-classes")
    parser.add_argument("--no-resize", action="store_true", help="Isolate steady-size animation")
    parser.add_argument("--platform", choices=("auto", "x11", "wayland"), default="auto")
    parser.add_argument("--context", choices=("native", "egl"), default="native", help="rawgl context API")
    args = parser.parse_args()
    if args.frames < 1:
        parser.error("--frames must be positive")
    if args.readback and args.backend == "vulkan":
        parser.error("--readback is currently supported only by OpenGL")
    if args.context != "native" and args.backend != "rawgl":
        parser.error("--context currently applies only to rawgl")
    root = Path(__file__).resolve().parents[1]
    trace = args.trace.resolve()
    trace.mkdir(parents=True, exist_ok=True)
    maven = shutil.which("mvn")
    java = shutil.which("java")
    if not maven or not java:
        parser.error("JDK and Maven must be on PATH")
    if not args.skip_build:
        subprocess.run([maven, "-q", "-pl", "desktop-host", "-am", "test-compile"], cwd=root, check=True)
    with tempfile.TemporaryDirectory(prefix="qplayer-probe-") as temporary:
        cp_file = Path(temporary) / "classpath.txt"
        subprocess.run([maven, "-q", "-pl", "desktop-host", "dependency:build-classpath",
                        f"-Dmdep.outputFile={cp_file}", "-DincludeScope=test"], cwd=root, check=True)
        cp = os.pathsep.join([str(root / "desktop-host/target/test-classes"),
                              str(root / "desktop-host/target/classes"),
                              str(root / "player-core/target/classes"), cp_file.read_text().strip()])
        before = set(trace.glob("*.csv"))
        command = [java, f"-Dqplayer.frameTrace={trace}",
                   f"-Dqplayer.frameReadback={str(args.readback).lower()}",
                   f"-Dqplayer.probe.resize={str(not args.no_resize).lower()}",
                   f"-Dqplayer.probe.platform={args.platform}",
                   f"-Dqplayer.probe.context={args.context}",
                   f"-Dqplayer.probe.frames={args.frames}", "-cp", cp,
                   "dev.t1m3.qplayer.desktop.window." +
                   ("RawGlPresentationProbe" if args.backend == "rawgl" else "BackendPresentationProbe"), args.backend]
        result = subprocess.run(command, cwd=root, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, text=True, errors="replace")
    log = trace / f"{args.backend}-probe.log"
    log.write_text(result.stdout, encoding="utf-8")
    print(result.stdout, end="")
    print(f"Probe log: {log}")
    if result.returncode or "PROBE_CLEANUP_DONE" not in result.stdout:
        raise SystemExit("GPU probe did not complete successfully")
    if "[LWJGL] GLFW_" in result.stdout:
        raise SystemExit("GLFW reported an error; frame readback alone cannot validate presentation")
    if "Validation Error" in result.stdout or "SYNC-HAZARD" in result.stdout:
        raise SystemExit("Vulkan validation reported an error; inspect the probe log")
    files = set(trace.glob("*.csv")) - before
    if len(files) != 1:
        raise SystemExit(f"Expected one new frame trace, found {len(files)}")
    file = files.pop()
    with file.open(newline="", encoding="utf-8") as stream:
        rows = list(csv.DictReader(stream))
    if len(rows) != args.frames:
        raise SystemExit(f"Expected {args.frames} frame rows, found {len(rows)}")
    previous_return = 0
    for expected, row in enumerate(rows, 1):
        if int(row["sequence"]) != expected:
            raise SystemExit(f"CPU sequence mismatch: {row}")
        drawn, returned = int(row["draw_ns"]), int(row["present_return_ns"])
        if not previous_return <= drawn <= returned:
            raise SystemExit(f"Non-monotonic frame timestamps: {row}")
        previous_return = returned
        if args.readback and int(row["gpu_sequence"]) != (expected & 0xFFFFFF):
            raise SystemExit(f"GPU marker mismatch: {row}")
        if args.readback and args.backend == "rawgl" and int(row["wrong_scene_pixels"]) != 0:
            raise SystemExit(f"Raw GL scene pixels mismatch: {row}")
    print(f"PASS: {len(rows)} submitted frames" + (", GL GPU markers matched" if args.readback else ""))
    print("This checks submission, not the order actually displayed on the monitor.")


if __name__ == "__main__":
    main()
