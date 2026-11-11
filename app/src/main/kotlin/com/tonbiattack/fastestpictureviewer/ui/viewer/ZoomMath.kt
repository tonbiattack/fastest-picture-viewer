package com.tonbiattack.fastestpictureviewer.ui.viewer

import androidx.compose.ui.geometry.Offset

/** ダブルタップズームの中心・移動範囲を計算する純粋ロジック */
object ZoomMath {

    /**
     * タップ位置が拡大後も同じ画面位置に残るよう、画像中心基準のオフセットを求める。
     * 画面外へはみ出さないよう [clampOffset] で制限する。
     */
    fun doubleTapOffset(
        tap: Offset,
        viewportWidth: Int,
        viewportHeight: Int,
        targetScale: Float
    ): Offset {
        if (viewportWidth <= 0 || viewportHeight <= 0 || targetScale <= 1f) return Offset.Zero
        val centerX = viewportWidth / 2f
        val centerY = viewportHeight / 2f
        val raw = Offset(
            x = (tap.x - centerX) * (1f - targetScale),
            y = (tap.y - centerY) * (1f - targetScale)
        )
        return clampOffset(raw, viewportWidth, viewportHeight, targetScale)
    }

    /** 拡大時に画像の端より外側が見えないようオフセットを制限する */
    fun clampOffset(
        offset: Offset,
        viewportWidth: Int,
        viewportHeight: Int,
        scale: Float
    ): Offset {
        if (scale <= 1f || viewportWidth <= 0 || viewportHeight <= 0) return Offset.Zero
        val maxX = viewportWidth * (scale - 1f) / 2f
        val maxY = viewportHeight * (scale - 1f) / 2f
        return Offset(
            x = offset.x.coerceIn(-maxX, maxX),
            y = offset.y.coerceIn(-maxY, maxY)
        )
    }
}
