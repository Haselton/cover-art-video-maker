#!/usr/bin/env python3
"""Run against REAL emulator exports; no replacement videos are manufactured here."""
import array
import hashlib
import json
import math
from pathlib import Path
import subprocess
import sys


def run(*args):
    return subprocess.check_output(args)


def probe(path):
    return json.loads(run("ffprobe", "-v", "error", "-show_streams", "-show_packets", "-of", "json", str(path)))


def tone(samples, channel, start, end, rate=44100):
    data = samples[int(start * rate) * 2 + channel:int(end * rate) * 2:2]
    assert len(data) > 100, "Missing audio segment"
    rms = math.sqrt(sum(x*x for x in data) / len(data))
    assert rms > 0.08, f"Silent or severely attenuated segment: RMS={rms}"
    upward = sum(a <= 0 < b for a, b in zip(data, data[1:]))
    return upward * rate / (len(data) - 1)


def check(directory, name, duration, source_channels, dimensions):
    path = directory / (name + ".mp4")
    p = probe(path)
    video = next(s for s in p["streams"] if s["codec_type"] == "video")
    audio = next(s for s in p["streams"] if s["codec_type"] == "audio")
    assert video["codec_name"] == "h264", video
    assert video["pix_fmt"] == "yuv420p", video
    assert audio["codec_name"] == "aac" and audio["profile"] == "LC", audio
    assert int(audio["sample_rate"]) == 44100 and audio["channels"] == 2, audio
    actual_dimensions = (video["width"], video["height"])
    rotation = int(video.get("tags", {}).get("rotate", 0))
    for side in video.get("side_data_list", []):
        rotation = int(side.get("rotation", rotation))
    if abs(rotation) % 180 == 90:
        actual_dimensions = actual_dimensions[::-1]
    assert actual_dimensions == dimensions, (name, actual_dimensions, dimensions)
    pts = sorted(float(x["pts_time"]) for x in p["packets"] if x["stream_index"] == video["index"])
    assert len(pts) > 1 and abs(pts[0]) < .08, (name, pts[:3])
    assert all(abs(b-a-.04) < .00001 for a, b in zip(pts, pts[1:])), "Not constant 25 fps"
    video_duration = pts[-1] - pts[0] + .04
    assert abs(video_duration - duration) < .12, (name, video_duration, duration)
    audio_pts = [float(x["pts_time"]) for x in p["packets"] if x["stream_index"] == audio["index"]]
    assert all(b > a for a, b in zip(audio_pts, audio_pts[1:])), "Repeated audio timestamps"
    samples = array.array("f", run("ffmpeg", "-v", "error", "-i", str(path), "-map", "0:a:0", "-f", "f32le", "-c:a", "pcm_f32le", "-"))
    decoded_duration = len(samples) / (44100 * 2)
    assert abs(decoded_duration - duration) < .12, (name, decoded_duration, duration)
    expected_right = 880 if source_channels == 2 else 440
    pitch = [tone(samples, 0, .3, 2.3), tone(samples, 1, .3, 2.3)]
    assert abs(pitch[0] - 440) < 4 and abs(pitch[1] - expected_right) < 4, (name, "speed/pitch corruption", pitch)
    tail = tone(samples, 0, duration - .16, duration - .06)
    assert abs(tail - 1200) < 20, (name, "ending missing or time-shifted", tail)
    decoded = subprocess.run(["ffmpeg", "-v", "warning", "-i", str(path), "-f", "null", "-"], capture_output=True)
    assert decoded.returncode == 0 and not decoded.stderr.strip(), decoded.stderr.decode(errors="replace")
    result = {"file": path.name, "video": "H.264 yuv420p 25 fps", "audio": "AAC-LC 44100 Hz stereo",
              "dimensions": actual_dimensions, "frames": len(pts), "decoded_duration_s": decoded_duration,
              "pitch_hz": pitch, "ending_tone_hz": tail, "decode_warnings": 0}
    print(json.dumps(result), flush=True)
    return result


def main():
    directory = Path(sys.argv[1])
    results = []
    cases = [
        ("stereo_44100", 3.24, 2, (1920, 1080)),
        ("stereo_48000", 3.24, 2, (1920, 1080)),
        ("mono_48000", 3.24, 1, (1080, 1920)),
        ("pcm24_48000", 3.24, 2, (1080, 1080)),
        ("long_48000", 61.24, 2, (320, 240)),
        ("aac_source", 3.24, 2, (640, 360)),
        ("aac_copy", 3.24, 2, (640, 360)),
    ]
    for case in cases:
        results.append(check(directory, *case))
    def audio_hash(name):
        data = run("ffmpeg", "-v", "error", "-i", str(directory / (name + ".mp4")), "-map", "0:a:0", "-c:a", "copy", "-f", "adts", "-")
        return hashlib.sha256(data).hexdigest()
    assert audio_hash("aac_source") == audio_hash("aac_copy"), "Compatible AAC was unnecessarily re-encoded"
    report = {"passed": True, "aac_copy_bit_exact": True, "exports": results}
    (directory / "verification.json").write_text(json.dumps(report, indent=2) + "\n")
    print("PASS: all seven actual Android exports; compatible AAC packets are bit-identical.")


if __name__ == "__main__":
    main()
