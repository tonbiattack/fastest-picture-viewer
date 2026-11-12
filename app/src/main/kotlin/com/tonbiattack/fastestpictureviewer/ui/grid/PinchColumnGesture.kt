package com.tonbiattack.fastestpictureviewer.ui.grid

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged

/**
 * 2 本指のピンチで一時的にグリッド列数を変えるジェスチャー (Issue #45)。
 *
 * - ピンチインで列数を増やし、ピンチアウトで減らす
 * - 1 本指のスクロールやタップは消費しない
 * - 指を離すと [onPreviewChange] に null を渡し、保存済みの列数へ戻す
 *
 * @param baseColumns 保存済みの列数 (ピンチ開始時の基準)
 * @param onPreviewChange ピンチ中の一時列数。終了時は null
 */
fun Modifier.pinchToColumns(
    baseColumns: Int,
    onPreviewChange: (Int?) -> Unit
): Modifier = pointerInput(baseColumns) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var cumulativeScale = 1f
        var pinching = false
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pressedCount = event.changes.count { it.pressed }
            if (pressedCount >= 2) {
                val zoom = event.calculateZoom()
                if (zoom.isFinite() && zoom > 0f && zoom != 1f) {
                    pinching = true
                    cumulativeScale *= zoom
                    onPreviewChange(
                        PinchColumnMath.targetColumns(baseColumns, cumulativeScale)
                    )
                    // ピンチ中だけ消費し、1 本指のスクロールには影響させない
                    event.changes.forEach { change ->
                        if (change.positionChanged()) change.consume()
                    }
                }
            }
        } while (event.changes.any { it.pressed })

        if (pinching) {
            // 指を離したら保存済みの列数へ復帰する
            onPreviewChange(null)
        }
    }
}
