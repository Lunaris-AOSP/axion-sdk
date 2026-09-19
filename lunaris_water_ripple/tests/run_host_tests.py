#!/usr/bin/env python3
"""Run native rendering regressions with host EGL/GLES2, a C compiler and Pillow."""
import os
from pathlib import Path
import struct
import subprocess
import tempfile

from PIL import Image

module = Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix="lunaris-water-tests-") as work:
    work = Path(work)
    for source in (module / "res/drawable-nodpi").iterdir():
        with Image.open(source) as image:
            image = image.convert("RGBA")
            (work / (source.stem + ".rgba")).write_bytes(
                struct.pack("=II", *image.size) + image.tobytes()
            )
    native = module / "native"
    sources = ["ripple_core.c", "ripple_gles_shaders.c", "ripple_gles_pipeline.c",
               "ripple_gles_overlay_shader.c", "ripple_gles_overlay.c", "watercolor_refresh.c"]
    command = [os.environ.get("CC", "cc"), "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror",
               "-fno-fast-math", "-ffp-contract=off", "-I" + str(module / "tests/include"),
               "-I" + str(native), str(module / "tests/render_test.c")]
    command += [str(native / source) for source in sources]
    command += ["-lEGL", "-lGLESv2", "-lm", "-o", str(work / "render_test")]
    subprocess.run(command, check=True)
    subprocess.run([str(work / "render_test"), str(work)], check=True,
                   env={**os.environ, "LIBGL_ALWAYS_SOFTWARE": "1"})
