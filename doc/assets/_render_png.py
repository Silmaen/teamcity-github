"""One-shot SVG→PNG rasteriser for logo.svg and logo-wordmark.svg.

We don't have rsvg-convert / inkscape / a working npm in this env, so we
reproduce the SVG paths directly with PIL. Rendered at 4x and downsampled
with LANCZOS for clean anti-aliasing.
"""
from PIL import Image, ImageDraw, ImageFont

SCALE = 4
FONT_BOLD = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"

def rgb(h):
    h = h.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), 255)

def cubic(p0, p1, p2, p3, n=400):
    out = []
    for i in range(n + 1):
        t = i / n
        u = 1 - t
        x = u**3 * p0[0] + 3*u**2*t*p1[0] + 3*u*t**2*p2[0] + t**3*p3[0]
        y = u**3 * p0[1] + 3*u**2*t*p1[1] + 3*u*t**2*p2[1] + t**3*p3[1]
        out.append((x, y))
    return out

def stamp(draw, pts, color, width):
    r = width / 2
    for x, y in pts:
        draw.ellipse([x - r, y - r, x + r, y + r], fill=color)

def polyline_pts(pts, n=200):
    out = []
    for i in range(len(pts) - 1):
        x1, y1 = pts[i]
        x2, y2 = pts[i + 1]
        for k in range(n + 1):
            t = k / n
            out.append((x1 + (x2 - x1) * t, y1 + (y2 - y1) * t))
    return out

def render_icon(draw, ox, oy, size):
    """Render the 256-unit icon design into (ox,oy) at the given pixel size."""
    s = size / 256
    def P(x, y): return (ox + x * s, oy + y * s)
    bg    = rgb("#0D1117")
    tc    = rgb("#FF6F3C")
    gh    = rgb("#C9D1D9")
    green = rgb("#238636")
    white = (255, 255, 255, 255)
    # rounded background
    draw.rounded_rectangle([ox, oy, ox + size, oy + size], radius=56 * s, fill=bg)
    # two bezier arcs
    stamp(draw, cubic(P(56, 60),  P(56, 132),  P(128, 128), P(128, 172)), tc, 16 * s)
    stamp(draw, cubic(P(200, 60), P(200, 132), P(128, 128), P(128, 172)), gh, 16 * s)
    # endpoint dots
    for cx, cy, r, c in [(56, 60, 14, tc), (200, 60, 14, gh)]:
        x, y = P(cx, cy)
        rr = r * s
        draw.ellipse([x - rr, y - rr, x + rr, y + rr], fill=c)
    # green check badge with bg-coloured halo
    cx, cy = P(128, 194)
    R, BW = 32 * s, 6 * s
    draw.ellipse([cx - R - BW/2, cy - R - BW/2, cx + R + BW/2, cy + R + BW/2], fill=bg)
    draw.ellipse([cx - R + BW/2, cy - R + BW/2, cx + R - BW/2, cy + R - BW/2], fill=green)
    # checkmark
    stamp(draw, polyline_pts([P(113, 194), P(124, 205), P(144, 184)]), white, 6 * s)

# logo.png — 256x256
img = Image.new("RGBA", (256 * SCALE, 256 * SCALE), (0, 0, 0, 0))
render_icon(ImageDraw.Draw(img), 0, 0, 256 * SCALE)
img.resize((256, 256), Image.LANCZOS).save("doc/assets/logo.png")

# logo-wordmark.png — 720x220, transparent bg
img = Image.new("RGBA", (720 * SCALE, 220 * SCALE), (0, 0, 0, 0))
d = ImageDraw.Draw(img)
render_icon(d, 20 * SCALE, 20 * SCALE, 180 * SCALE)
font_big = ImageFont.truetype(FONT_BOLD, 48 * SCALE)
font_med = ImageFont.truetype(FONT_BOLD, 36 * SCALE)
x0 = 230 * SCALE
yb = 108 * SCALE
w1 = d.textlength("teamcity", font=font_big)
w2 = d.textlength("-", font=font_big)
d.text((x0,        yb), "teamcity", font=font_big, fill=rgb("#FF6F3C"), anchor="ls")
d.text((x0 + w1,   yb), "-",        font=font_big, fill=rgb("#0D1117"), anchor="ls")
d.text((x0 + w1+w2, yb), "github",  font=font_big, fill=rgb("#24292F"), anchor="ls")
d.text((230 * SCALE, 160 * SCALE), "bridge", font=font_med, fill=rgb("#57606A"), anchor="ls")
d.rectangle([232 * SCALE, 172 * SCALE, (232 + 48) * SCALE, (172 + 3) * SCALE], fill=rgb("#238636"))
img.resize((720, 220), Image.LANCZOS).save("doc/assets/logo-wordmark.png")

print("OK")
