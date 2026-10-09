"""Turbo for Distant Horizons logo: the Distant Horizons cube with a lightning bolt on top."""
import sys

from PIL import Image, ImageDraw

SRC, OUT = sys.argv[1], sys.argv[2]
SIZE = 1024
OUTLINE = 34
BLACK = (0, 0, 0, 255)
YELLOW = (255, 206, 46, 255)
YELLOW_LIGHT = (255, 232, 130, 255)
ORANGE = (255, 150, 30, 255)

base = Image.open(SRC).convert("RGBA").resize((SIZE, SIZE), Image.LANCZOS)
# Shrink the cube a little towards the upper left to make room for the bolt.
cube = base.resize((880, 880), Image.LANCZOS)
canvas = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
canvas.alpha_composite(cube, (40, 20))

layer = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
draw = ImageDraw.Draw(layer)


def outlined_polygon(points, fill, width=OUTLINE):
    draw.line(points + [points[0], points[1]], fill=BLACK, width=width * 2, joint="curve")
    for x, y in points:
        draw.ellipse((x - width, y - width, x + width, y + width), fill=BLACK)
    draw.polygon(points, fill=BLACK)
    draw.polygon(points, fill=fill)


# Lightning bolt in the lower right corner.
bolt = [(800, 400), (580, 740), (725, 740), (640, 995), (975, 600), (820, 600), (930, 400)]
outlined_polygon(bolt, YELLOW)
# Highlight on the upper half and a warm shade on the lower tip, flat like the DH style.
draw.polygon([(800, 400), (930, 400), (897, 455), (765, 455)], fill=YELLOW_LIGHT)
draw.polygon([(725, 740), (640, 995), (700, 915), (758, 740)], fill=ORANGE)

canvas.alpha_composite(layer)
canvas.resize((512, 512), Image.LANCZOS).save(OUT)
