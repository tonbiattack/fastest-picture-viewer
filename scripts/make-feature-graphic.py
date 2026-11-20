#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
Google Play のフィーチャーグラフィックを生成する (Issue #121)。

サイズは 1024x500。文字を入れず、アプリの雰囲気（写真カードと小鳥）で構成する。
文字を焼き込むと翻訳・差し替えが効かなくなるため、図形だけで作る。

出力:
  store/feature-graphic-1024x500.png

使い方:
  python -X utf8 scripts/make-feature-graphic.py
"""

from __future__ import annotations

import os
import sys

from PIL import Image, ImageDraw, ImageFilter

WIDTH = 1024
HEIGHT = 500

BACKGROUND_TOP = (0x0E, 0x1B, 0x2A)
BACKGROUND_BOTTOM = (0x14, 0x2E, 0x42)

CARD_FILL = (0x17, 0x3C, 0x55)
CARD_STROKE = (0x8F, 0xEB, 0xDD)
LANDSCAPE = (0x2C, 0x6B, 0x78)
SUN = (0xFF, 0xB8, 0x6B)
BIRD_BODY = (0x7F, 0xE7, 0xD4)
BIRD_BELLY = (0xD8, 0xFF, 0xF7)
BIRD_WING = (0x3C, 0xC7, 0xB3)
BEAK = (0xFF, 0xB8, 0x6B)
EYE = (0x0B, 0x15, 0x24)


def lerp(a: tuple[int, int, int], b: tuple[int, int, int], t: float) -> tuple[int, int, int]:
    return (
        int(a[0] + (b[0] - a[0]) * t),
        int(a[1] + (b[1] - a[1]) * t),
        int(a[2] + (b[2] - a[2]) * t),
    )


def draw_card(
    draw: ImageDraw.ImageDraw,
    left: int,
    top: int,
    width: int,
    height: int,
    angle_hint: int,
    accent: tuple[int, int, int],
) -> None:
    """写真カードを 1 枚描く。angle_hint で傾きの向きを変える"""
    # 傾きは台形のずれで表現する（回転より単純で、にじみが出ない）
    skew = angle_hint
    points = [
        (left + skew, top),
        (left + width + skew, top),
        (left + width, top + height),
        (left, top + height),
    ]
    draw.polygon(points, fill=CARD_FILL, outline=CARD_STROKE, width=4)

    # 中の風景
    inner_left = left + 18
    inner_top = top + 18
    inner_right = left + width - 18
    inner_bottom = top + height - 18
    draw.rectangle([inner_left, inner_top, inner_right, inner_bottom], fill=accent)

    # 山
    mid = (inner_left + inner_right) // 2
    draw.polygon(
        [
            (inner_left, inner_bottom),
            (mid - 40, inner_top + int((inner_bottom - inner_top) * 0.45)),
            (mid + 30, inner_bottom),
        ],
        fill=LANDSCAPE,
    )
    # 太陽
    sun_r = 26
    sun_cx = inner_right - 60
    sun_cy = inner_top + 60
    draw.ellipse(
        [sun_cx - sun_r, sun_cy - sun_r, sun_cx + sun_r, sun_cy + sun_r],
        fill=SUN,
    )


def main() -> int:
    project_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    out_dir = os.path.join(project_root, "store")
    os.makedirs(out_dir, exist_ok=True)

    # 背景のグラデーション
    image = Image.new("RGB", (WIDTH, HEIGHT), BACKGROUND_TOP)
    draw = ImageDraw.Draw(image)
    for y in range(HEIGHT):
        draw.line(
            [(0, y), (WIDTH, y)],
            fill=lerp(BACKGROUND_TOP, BACKGROUND_BOTTOM, y / (HEIGHT - 1)),
        )

    # 奥に薄い円を置いて奥行きを出す
    glow = Image.new("RGB", (WIDTH, HEIGHT), (0, 0, 0))
    glow_draw = ImageDraw.Draw(glow)
    glow_draw.ellipse([WIDTH - 420, -160, WIDTH + 160, 420], fill=(0x1E, 0x4A, 0x5C))
    glow = glow.filter(ImageFilter.GaussianBlur(radius=90))
    image = Image.blend(image, Image.blend(image, glow, 0.55), 0.55)
    draw = ImageDraw.Draw(image)

    # 写真カードを 3 枚、少しずつずらして重ねる
    draw_card(draw, 96, 150, 250, 200, 18, (0x2A, 0x3F, 0x6A))
    draw_card(draw, 176, 118, 250, 200, 10, (0x3A, 0x2E, 0x58))
    draw_card(draw, 256, 92, 250, 200, 0, (0x1F, 0x4A, 0x52))

    # 小鳥をカードの右上に置く
    body_cx, body_cy = 560, 150
    body_rx, body_ry = 62, 70
    draw.ellipse(
        [body_cx - body_rx, body_cy - body_ry, body_cx + body_rx, body_cy + body_ry],
        fill=BIRD_BODY,
    )
    # おなか
    draw.ellipse(
        [body_cx - 38, body_cy + 6, body_cx + 38, body_cy + 74],
        fill=BIRD_BELLY,
    )
    # 翼
    draw.ellipse(
        [body_cx - 46, body_cy - 26, body_cx + 46, body_cy + 36],
        fill=BIRD_WING,
    )
    # 頭
    head_cx, head_cy = 596, 74
    draw.ellipse(
        [head_cx - 40, head_cy - 40, head_cx + 40, head_cy + 40],
        fill=BIRD_BODY,
    )
    # くちばし
    draw.polygon(
        [(head_cx + 34, head_cy - 6), (head_cx + 92, head_cy + 10), (head_cx + 34, head_cy + 26)],
        fill=BEAK,
    )
    # 目
    draw.ellipse(
        [head_cx + 8, head_cy - 14, head_cx + 26, head_cy + 4],
        fill=EYE,
    )
    # 尾羽
    draw.polygon(
        [(body_cx - 50, body_cy - 20), (body_cx - 130, body_cy - 96),
         (body_cx - 112, body_cy - 30), (body_cx - 150, body_cy - 40),
         (body_cx - 74, body_cy + 26)],
        fill=(0x5F, 0xE0, 0xCB),
    )
    # 足
    for dx in (-22, 22):
        draw.line(
            [(body_cx + dx, body_cy + 68), (body_cx + dx - 6, body_cy + 118)],
            fill=BEAK,
            width=8,
        )

    path = os.path.join(out_dir, "feature-graphic-1024x500.png")
    image.save(path, "PNG")
    print(f"保存: {path} ({WIDTH}x{HEIGHT})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
