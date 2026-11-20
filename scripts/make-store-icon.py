#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
Pictobi のストア用アイコン PNG を生成する (Issue #121)。

`app/src/main/res/drawable/ic_launcher_foreground.xml` と同じ図形を Pillow で描き直す。
ベクターをそのままラスタライズするツール（cairosvg / Inkscape）が無い環境でも
再生成できるよう、図形を Python で持つ。

出力:
  store/icon-512.png        … Google Play のストアアイコン (512x512, 32bit PNG)
  store/icon-1024.png       … 高解像度の控え (1024x1024)

使い方:
  python -X utf8 scripts/make-store-icon.py
"""

from __future__ import annotations

import os
import sys

from PIL import Image, ImageDraw

# アイコンの背景色。res/values/ic_launcher_background.xml と同じ値
BACKGROUND = (0x0E, 0x1B, 0x2A)

# 前景の色。ic_launcher_foreground.xml と同じ値
CARD_BACK_FILL = (0x14, 0x3A, 0x52)
CARD_BACK_STROKE = (0x3B, 0x7C, 0x8C)
CARD_FRONT_FILL = (0x17, 0x3C, 0x55)
CARD_FRONT_STROKE = (0x8F, 0xEB, 0xDD)
LANDSCAPE = (0x2C, 0x6B, 0x78)
SUN = (0xFF, 0xB8, 0x6B)
BIRD_TAIL = (0x5F, 0xE0, 0xCB)
BIRD_BODY = (0x7F, 0xE7, 0xD4)
BIRD_BELLY = (0xD8, 0xFF, 0xF7)
BIRD_WING = (0x3C, 0xC7, 0xB3)
BEAK = (0xFF, 0xB8, 0x6B)
EYE = (0x0B, 0x15, 0x24)

# ベクターの viewport は 108x108。中央 66x66 の安全領域に収めるため 0.9 倍する
VIEWPORT = 108.0
SCALE = 0.9


def to_px(x: float, y: float, size: int) -> tuple[float, float]:
    """ベクター座標 (108x108) を出力画像のピクセル座標へ変換する。

    0.9 倍に縮めたうえで、中央 (54, 54) を軸に戻す。
    """
    cx = cy = VIEWPORT / 2.0
    sx = cx + (x - cx) * SCALE
    sy = cy + (y - cy) * SCALE
    factor = size / VIEWPORT
    return sx * factor, sy * factor


def scaled(points: list[tuple[float, float]], size: int) -> list[tuple[float, float]]:
    return [to_px(x, y, size) for x, y in points]


def draw_icon(size: int) -> Image.Image:
    """指定サイズのアイコンを描く。"""
    # 高解像度で描いてから縮小すると輪郭が滑らかになる
    supersample = 4
    canvas = size * supersample
    image = Image.new("RGB", (canvas, canvas), BACKGROUND)
    draw = ImageDraw.Draw(image)

    def width(px: float) -> int:
        """線幅もスケールさせる。最低 1px を確保する"""
        return max(1, int(round(px * canvas / VIEWPORT)))

    def radius(px: float) -> float:
        return to_px(px, 0, canvas)[0] - to_px(0, 0, canvas)[0]

    # 背面の写真カード (少し傾ける)
    draw.polygon(
        scaled([(42, 27), (80, 33), (75, 73), (37, 67)], canvas),
        fill=CARD_BACK_FILL,
        outline=CARD_BACK_STROKE,
        width=width(2),
    )

    # 正面の写真カード (角丸)
    card = scaled([(28, 38), (80, 38), (80, 86), (28, 86)], canvas)
    draw.rounded_rectangle(
        [card[0], card[2]],
        radius=radius(6),
        fill=CARD_FRONT_FILL,
        outline=CARD_FRONT_STROKE,
        width=width(2.5),
    )

    # 写真の中の風景 (山)
    draw.polygon(
        scaled([(30, 80), (42, 62), (52, 72), (60, 64), (78, 80)], canvas),
        fill=LANDSCAPE,
    )

    # 写真の中の太陽
    sx, sy = to_px(43, 50, canvas)
    r = radius(4)
    draw.ellipse([sx - r, sy - r, sx + r, sy + r], fill=SUN)

    # 小鳥の尾羽。ベクターの 5 点をそのまま使う
    draw.polygon(
        scaled([(57, 39), (45, 24), (48, 36), (41, 34), (50, 46)], canvas),
        fill=BIRD_TAIL,
    )

    # 小鳥の体。ベクターは 3 次ベジェで丸い体を描いているため、
    # 楕円で近似する（頂点を多角形で結ぶと角ばる）
    body_cx, body_cy = to_px(63, 47.5, canvas)
    body_rx = radius(13)
    body_ry = radius(14.5)
    draw.ellipse(
        [body_cx - body_rx, body_cy - body_ry, body_cx + body_rx, body_cy + body_ry],
        fill=BIRD_BODY,
    )

    # おなか
    belly_cx, belly_cy = to_px(63.5, 52.5, canvas)
    belly_rx = radius(8.5)
    belly_ry = radius(8.5)
    draw.ellipse(
        [belly_cx - belly_rx, belly_cy - belly_ry, belly_cx + belly_rx, belly_cy + belly_ry],
        fill=BIRD_BELLY,
    )

    # 翼。ベクターの曲線を楕円で近似し、体の左側に置く
    wing_cx, wing_cy = to_px(63.5, 47.5, canvas)
    wing_rx = radius(9.5)
    wing_ry = radius(6.5)
    draw.ellipse(
        [wing_cx - wing_rx, wing_cy - wing_ry, wing_cx + wing_rx, wing_cy + wing_ry],
        fill=BIRD_WING,
    )

    # 頭
    hx, hy = to_px(71, 31, canvas)
    hr = radius(7)
    draw.ellipse([hx - hr, hy - hr, hx + hr, hy + hr], fill=BIRD_BODY)

    # くちばし
    draw.polygon(scaled([(77, 35), (86, 38), (77, 41)], canvas), fill=BEAK)

    # 目
    ex, ey = to_px(73, 34, canvas)
    er = radius(1.8)
    draw.ellipse([ex - er, ey - er, ex + er, ey + er], fill=EYE)

    # 足
    for x1, y1, x2, y2 in ((62, 61, 61, 68), (68, 61, 69, 68)):
        draw.line(
            [to_px(x1, y1, canvas), to_px(x2, y2, canvas)],
            fill=BEAK,
            width=width(1.8),
        )

    return image.resize((size, size), Image.LANCZOS)


def main() -> int:
    project_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    out_dir = os.path.join(project_root, "store")
    os.makedirs(out_dir, exist_ok=True)

    for size, name in ((512, "icon-512.png"), (1024, "icon-1024.png")):
        image = draw_icon(size)
        path = os.path.join(out_dir, name)
        # Google Play のストアアイコンは 32bit PNG (アルファ付き) が求められる
        image.convert("RGBA").save(path, "PNG")
        print(f"保存: {path} ({size}x{size})")

    return 0


if __name__ == "__main__":
    sys.exit(main())
