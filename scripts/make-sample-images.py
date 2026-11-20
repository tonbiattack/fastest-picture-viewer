#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
ストア掲載用スクリーンショットのサンプル画像を生成する (Issue #121)。

**実在の写真は使わない。** 第三者の個人情報を含めないため、図形だけで
「写真らしい」画像を合成する。生成した画像を端末へ入れて撮影する。

出力:
  store/sample-images/<folder>/<name>.png

使い方:
  python -X utf8 scripts/make-sample-images.py
"""

from __future__ import annotations

import math
import os
import sys

from PIL import Image, ImageDraw, ImageFilter

# 出力サイズ。端末の画面に合わせて十分な解像度にする
WIDTH = 1080
HEIGHT = 1440


def lerp(a: tuple[int, int, int], b: tuple[int, int, int], t: float) -> tuple[int, int, int]:
    """2 色を t (0..1) で混ぜる"""
    return (
        int(a[0] + (b[0] - a[0]) * t),
        int(a[1] + (b[1] - a[1]) * t),
        int(a[2] + (b[2] - a[2]) * t),
    )


def vertical_gradient(
    top: tuple[int, int, int],
    bottom: tuple[int, int, int],
    width: int = WIDTH,
    height: int = HEIGHT,
) -> Image.Image:
    """上から下へグラデーションする背景"""
    image = Image.new("RGB", (width, height), top)
    draw = ImageDraw.Draw(image)
    for y in range(height):
        draw.line([(0, y), (width, y)], fill=lerp(top, bottom, y / max(1, height - 1)))
    return image


def draw_sunset(seed: int = 0) -> Image.Image:
    """夕焼けの海。山と太陽と水面の反射。seed で太陽の位置と色を変える"""
    # seed で空の色を少し変える
    top = lerp((0x2B, 0x1B, 0x4A), (0x1A, 0x2A, 0x4A), (seed % 3) / 2.0)
    bottom = lerp((0xF2, 0x8C, 0x4B), (0xE8, 0x6A, 0x8A), (seed % 3) / 2.0)
    image = vertical_gradient(top, bottom)
    draw = ImageDraw.Draw(image)

    # 太陽
    sun_y = int(HEIGHT * (0.38 + 0.04 * (seed % 3)))
    sun_r = int(WIDTH * (0.11 + 0.02 * (seed % 2)))
    draw.ellipse(
        [WIDTH // 2 - sun_r, sun_y - sun_r, WIDTH // 2 + sun_r, sun_y + sun_r],
        fill=(0xFF, 0xD9, 0x8A),
    )

    # 遠くの山
    horizon = int(HEIGHT * 0.58)
    draw.polygon(
        [(0, horizon), (int(WIDTH * 0.22), int(HEIGHT * 0.40)),
         (int(WIDTH * 0.45), horizon)],
        fill=(0x3A, 0x2A, 0x55),
    )
    draw.polygon(
        [(int(WIDTH * 0.35), horizon), (int(WIDTH * 0.62), int(HEIGHT * 0.34)),
         (int(WIDTH * 0.92), horizon)],
        fill=(0x2E, 0x22, 0x48),
    )

    # 海
    draw.rectangle([0, horizon, WIDTH, HEIGHT], fill=(0x1B, 0x2A, 0x4A))

    # 水面の反射
    for i in range(40):
        y = horizon + int((HEIGHT - horizon) * (i / 40.0) ** 1.6)
        half = int(WIDTH * 0.16 * (1.0 - i / 50.0))
        alpha = 1.0 - i / 40.0
        color = lerp((0x1B, 0x2A, 0x4A), (0xFF, 0xC8, 0x7A), alpha * 0.75)
        draw.line([(WIDTH // 2 - half, y), (WIDTH // 2 + half, y)], fill=color, width=6)

    return image


def draw_forest(seed: int = 0) -> Image.Image:
    """霧の森。木のシルエットを奥行きごとに重ねる。seed で木の配置を変える"""
    image = vertical_gradient((0xE8, 0xF0, 0xE4), (0x6E, 0x8F, 0x6A))
    draw = ImageDraw.Draw(image)

    # 奥から手前へ、だんだん濃く
    layers = [
        (0.62, (0xC5, 0xD6, 0xC0), 0.055),
        (0.70, (0x9C, 0xB4, 0x96), 0.075),
        (0.80, (0x6E, 0x8C, 0x6A), 0.10),
        (0.92, (0x3F, 0x5A, 0x3E), 0.14),
    ]
    for base_ratio, color, width_ratio in layers:
        base = int(HEIGHT * base_ratio)
        step = int(WIDTH * width_ratio)
        for x in range(-step, WIDTH + step, step):
            height = int(HEIGHT * (0.16 + 0.10 * ((x // step + seed) % 3) / 2.0))
            draw.polygon(
                [(x, base), (x + step // 2, base - height), (x + step, base)],
                fill=color,
            )
        draw.rectangle([0, base, WIDTH, HEIGHT], fill=color)

    return image


def draw_city_night(seed: int = 0) -> Image.Image:
    """夜の街。窓明かりのビル群。seed でビルの高さと窓を変える"""
    image = vertical_gradient((0x0B, 0x14, 0x2A), (0x1E, 0x2A, 0x44))
    draw = ImageDraw.Draw(image)

    # 星
    for i in range(90):
        x = (i * 137 + seed * 53) % WIDTH
        y = (i * 89 + seed * 31) % int(HEIGHT * 0.45)
        r = 2 if i % 3 else 3
        draw.ellipse([x - r, y - r, x + r, y + r], fill=(0xE8, 0xEE, 0xFF))

    # ビル。seed で高さをずらす
    buildings = [
        (0.00, 0.34, 0.16, 0x1A, 0x24, 0x3C),
        (0.14, 0.46, 0.13, 0x22, 0x2E, 0x4A),
        (0.25, 0.28, 0.18, 0x16, 0x1F, 0x36),
        (0.41, 0.52, 0.12, 0x24, 0x31, 0x4E),
        (0.51, 0.38, 0.17, 0x1A, 0x24, 0x3C),
        (0.66, 0.60, 0.14, 0x28, 0x36, 0x54),
        (0.78, 0.44, 0.22, 0x1C, 0x27, 0x40),
    ]
    for index, (left_ratio, top_ratio, width_ratio, r, g, b) in enumerate(buildings):
        left = int(WIDTH * left_ratio)
        # seed で高さを少し変える
        shift = ((index + seed) % 3 - 1) * 0.04
        top = int(HEIGHT * (top_ratio + shift))
        right = left + int(WIDTH * width_ratio)
        draw.rectangle([left, top, right, HEIGHT], fill=(r, g, b))

        # 窓明かり
        win = 14
        gap = 30
        for wy in range(top + 26, HEIGHT - 20, gap):
            for wx in range(left + 16, right - win - 8, gap):
                if (wx // gap + wy // gap + seed) % 3 == 0:
                    draw.rectangle(
                        [wx, wy, wx + win, wy + win],
                        fill=(0xFF, 0xD9, 0x8A),
                    )

    return image


def draw_flowers(seed: int = 0) -> Image.Image:
    """花畑。色とりどりの花を散らす。seed で配置を変える"""
    image = vertical_gradient((0xBF, 0xE3, 0xF5), (0x8F, 0xC9, 0x7A))
    draw = ImageDraw.Draw(image)

    # 丘
    draw.ellipse(
        [-int(WIDTH * 0.3), int(HEIGHT * 0.55), int(WIDTH * 1.3), int(HEIGHT * 1.5)],
        fill=(0x7F, 0xB8, 0x6A),
    )

    palette = [
        (0xFF, 0x8F, 0xA8),
        (0xFF, 0xD1, 0x66),
        (0xC9, 0xA3, 0xFF),
        (0xFF, 0xFF, 0xFF),
        (0xFF, 0x9E, 0x5A),
    ]
    for i in range(220):
        x = (i * 211 + seed * 137) % WIDTH
        y = int(HEIGHT * 0.58) + ((i * 97 + seed * 61) % int(HEIGHT * 0.42))
        r = 10 + ((i + seed) % 4) * 4
        color = palette[(i + seed) % len(palette)]
        draw.ellipse([x - r, y - r, x + r, y + r], fill=color)
        draw.ellipse(
            [x - r // 3, y - r // 3, x + r // 3, y + r // 3],
            fill=(0xFF, 0xF3, 0xC4),
        )

    return image


def draw_abstract(seed: int = 0) -> Image.Image:
    """抽象。幾何学模様のグラデーション。seed で配置を変える"""
    image = vertical_gradient((0x1A, 0x1F, 0x3A), (0x3D, 0x2B, 0x5C))
    draw = ImageDraw.Draw(image)

    colors = [
        (0x5F, 0xE0, 0xCB),
        (0xFF, 0xB8, 0x6B),
        (0x8F, 0xEB, 0xDD),
        (0xC9, 0xA3, 0xFF),
    ]
    for i in range(26):
        cx = int(WIDTH * (0.5 + 0.42 * math.cos(i * 0.9 + seed * 0.7)))
        cy = int(HEIGHT * (0.5 + 0.42 * math.sin(i * 1.3 + seed * 0.5)))
        r = int(WIDTH * (0.06 + 0.05 * ((i * 7 + seed) % 5) / 4.0))
        color = colors[(i + seed) % len(colors)]
        draw.ellipse([cx - r, cy - r, cx + r, cy + r], fill=color)

    return image.filter(ImageFilter.GaussianBlur(radius=2))


def draw_mountains(seed: int = 0) -> Image.Image:
    """雪山。稜線を重ねる。seed で稜線を変える"""
    image = vertical_gradient((0x9E, 0xC8, 0xE8), (0xE9, 0xF2, 0xF8))
    draw = ImageDraw.Draw(image)

    ridges = [
        (0.52, (0x8F, 0xA8, 0xC4), 0.10),
        (0.62, (0x6E, 0x8A, 0xAC), 0.14),
        (0.74, (0x4A, 0x63, 0x86), 0.18),
    ]
    for base_ratio, color, amp_ratio in ridges:
        base = int(HEIGHT * base_ratio)
        points = [(0, HEIGHT)]
        steps = 9
        for i in range(steps + 1):
            x = int(WIDTH * i / steps)
            y = base - int(HEIGHT * amp_ratio * abs(math.sin(i * 1.7 + seed * 0.6)))
            points.append((x, y))
        points.append((WIDTH, HEIGHT))
        draw.polygon(points, fill=color)

        # 雪
        for i in range(1, steps):
            x = int(WIDTH * i / steps)
            y = base - int(HEIGHT * amp_ratio * abs(math.sin(i * 1.7 + seed * 0.6)))
            draw.polygon(
                [(x - 40, y + 60), (x, y), (x + 40, y + 60)],
                fill=(0xF7, 0xFB, 0xFF),
            )

    return image


def main() -> int:
    project_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    out_root = os.path.join(project_root, "store", "sample-images")

    # フォルダ名 → 生成する画像の並び。グリッドの見栄えを確かめられるよう、
    # 1 フォルダあたり 6〜9 枚にする
    plan: dict[str, list[tuple[str, object]]] = {
        "01_夕焼けの海": [("sunset-01", draw_sunset), ("sunset-02", draw_sunset),
                          ("sunset-03", draw_sunset), ("sunset-04", draw_sunset),
                          ("sunset-05", draw_sunset), ("sunset-06", draw_sunset)],
        "02_霧の森": [("forest-01", draw_forest), ("forest-02", draw_forest),
                      ("forest-03", draw_forest), ("forest-04", draw_forest),
                      ("forest-05", draw_forest), ("forest-06", draw_forest)],
        "03_夜の街": [("city-01", draw_city_night), ("city-02", draw_city_night),
                      ("city-03", draw_city_night), ("city-04", draw_city_night),
                      ("city-05", draw_city_night), ("city-06", draw_city_night),
                      ("city-07", draw_city_night), ("city-08", draw_city_night),
                      ("city-09", draw_city_night)],
        "04_花畑": [("flower-01", draw_flowers), ("flower-02", draw_flowers),
                    ("flower-03", draw_flowers), ("flower-04", draw_flowers),
                    ("flower-05", draw_flowers), ("flower-06", draw_flowers),
                    ("flower-07", draw_flowers), ("flower-08", draw_flowers)],
        "05_抽象": [("abstract-01", draw_abstract), ("abstract-02", draw_abstract),
                    ("abstract-03", draw_abstract), ("abstract-04", draw_abstract),
                    ("abstract-05", draw_abstract), ("abstract-06", draw_abstract)],
        "06_雪山": [("mountain-01", draw_mountains), ("mountain-02", draw_mountains),
                    ("mountain-03", draw_mountains), ("mountain-04", draw_mountains),
                    ("mountain-05", draw_mountains), ("mountain-06", draw_mountains),
                    ("mountain-07", draw_mountains)],
    }

    for folder, items in plan.items():
        folder_path = os.path.join(out_root, folder)
        os.makedirs(folder_path, exist_ok=True)
        for index, (name, factory) in enumerate(items):
            # 同じ関数でも seed を変えて、並べたときに単調にならないようにする
            image = factory(seed=index)  # type: ignore[operator]
            path = os.path.join(folder_path, f"{name}.png")
            image.save(path, "PNG")
            print(f"保存: {path}")

    print(f"\n合計 {sum(len(v) for v in plan.values())} 枚を {out_root} に生成しました。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
