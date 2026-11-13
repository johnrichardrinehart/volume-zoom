"""Record from the laptop's raw mic and report a test tone's level in dBFS.

usage: vz-meas.py SECONDS LABEL [FREQ]
"""
import subprocess
import sys

import numpy as np

secs = float(sys.argv[1])
label = sys.argv[2]
freq = float(sys.argv[3]) if len(sys.argv) > 3 else 1000.0
rate = 48000
raw = subprocess.run(
    ["timeout", str(secs + 0.6), "pw-record", "--target",
     "alsa_input.pci-0000_00_1f.3.analog-stereo", "--rate", str(rate), "--channels", "1",
     "--format", "f32", "-"],
    capture_output=True,
).stdout
x = np.frombuffer(raw[: len(raw) // 4 * 4], dtype=np.float32)[rate // 3 :]

n = rate // 4  # 250 ms Hann windows (4 Hz bins), 50% overlap
win = np.hanning(n)
segs = [x[i : i + n] for i in range(0, len(x) - n + 1, n // 2)]
f = np.fft.rfftfreq(n, 1 / rate)
band = np.abs(f - freq) <= 8
noise = (np.abs(f - freq) >= 40) & (np.abs(f - freq) <= 200)
norm = (win.sum() ** 2) / 2 * 1.5  # full-scale sine -> ~0 dBFS (Hann ENBW 1.5 bins)
tones, noises = [], []
for s in segs:
    p = np.abs(np.fft.rfft(s * win)) ** 2
    tones.append(p[band].sum())
    noises.append(p[noise].mean() * band.sum())
# Median over segments rejects transient room noise (speech, typing).
tone_db = 10 * np.log10(np.median(tones) / norm)
noise_db = 10 * np.log10(np.median(noises) / norm)
print(f"{label:>8} tone {tone_db:7.2f} dBFS  noise {noise_db:7.2f}  snr {tone_db - noise_db:5.1f}")
