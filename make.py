# 0.9.4: the "Update the app" / "Waiting for approval" videos (8 s, 1280x720, H.264 + silent AAC) and the update poster.
import math, subprocess, sys
from PIL import Image, ImageDraw, ImageFont
W, H, FPS, SECS = 1280, 720, 25, 8
B = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
R = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
def font(p, s): return ImageFont.truetype(p, s)

def frame(t, title, sub, arrow_up=True):
    im = Image.new("RGB", (W, H), (13, 13, 18))
    d = ImageDraw.Draw(im)
    # soft red glow at the bottom
    for i in range(60):
        a = int(40 * (1 - i / 60))
        d.ellipse([W/2 - 700 + i*6, H - 160 + i*2, W/2 + 700 - i*6, H + 260 - i*2], fill=(13 + a, 13, 18))
    d.text((W/2, 150), "Xstream  ·  StreamX", font=font(B, 34), fill=(229, 9, 20), anchor="mm")
    d.text((W/2, 300), title, font=font(B, 76), fill=(255, 255, 255), anchor="mm")
    d.text((W/2, 385), sub, font=font(R, 36), fill=(189, 183, 195), anchor="mm")
    # arrow, gently bouncing
    y0 = 520 + 14 * math.sin(t * 2 * math.pi / 1.4)
    cx = W / 2
    if arrow_up:
        d.polygon([(cx, y0 - 50), (cx - 46, y0), (cx - 16, y0), (cx - 16, y0 + 60), (cx + 16, y0 + 60), (cx + 16, y0), (cx + 46, y0)], fill=(229, 9, 20))
    else:
        d.ellipse([cx - 40, y0 - 40, cx + 40, y0 + 40], outline=(229, 9, 20), width=8)
        d.line([cx, y0, cx, y0 - 24], fill=(229, 9, 20), width=8); d.line([cx, y0, cx + 18, y0 + 10], fill=(229, 9, 20), width=8)
    fade = min(1.0, t / 0.5, (SECS - t) / 0.5)
    if fade < 1: im = Image.blend(Image.new("RGB", (W, H), (0, 0, 0)), im, max(0.0, fade))
    return im

def video(path, title, sub, up):
    cmd = ["ffmpeg", "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", str(FPS), "-i", "-",
           "-f", "lavfi", "-i", "anullsrc=channel_layout=stereo:sample_rate=44100", "-t", str(SECS),
           "-c:v", "libx264", "-profile:v", "main", "-pix_fmt", "yuv420p", "-preset", "slow", "-crf", "26", "-g", "50",
           "-c:a", "aac", "-b:a", "64k", "-shortest", "-movflags", "+faststart", path]
    p = subprocess.Popen(cmd, stdin=subprocess.PIPE)
    for i in range(FPS * SECS):
        p.stdin.write(frame(i / FPS, title, sub, up).tobytes())
    p.stdin.close(); p.wait()
    assert p.returncode == 0

out = sys.argv[1]
video(f"{out}/update-app.mp4", "Update the app", "Update for new features — open the app and tap Update", True)
video(f"{out}/approval.mp4", "Waiting for approval", "Your account is waiting to be approved", False)
# poster (landscape card art)
frame(2.0, "Update the app", "Update for new features", True).save(f"{out}/update-art.png", optimize=True)
