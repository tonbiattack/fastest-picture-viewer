package com.tonbiattack.fastestpictureviewer.core.image

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil.decode.DataSource
import coil.request.ImageResult
import com.tonbiattack.fastestpictureviewer.data.ImageItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * ビューアのリクエスト設定のテスト (Issue #101)。
 *
 * 先読みと表示で設定が食い違うとメモリキャッシュが再利用されず、スワイプのたびに
 * デコードが走る。そのため「同じ入力からは同じリクエスト」であることを固定する。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ViewerImageRequestTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun item(
        id: String = "content://media/external/images/media/1",
        modifiedSec: Long = 100L,
        size: Long = 1000L
    ) = ImageItem(
        id = id,
        mediaId = 1L,
        volumeName = "external_primary",
        uri = id,
        displayName = "a.jpg",
        sortName = "a.jpg",
        relativePath = "Pictures/",
        folderKey = "external_primary:Pictures/",
        folderName = "Pictures",
        dateModifiedSec = modifiedSec,
        size = size,
        mimeType = "image/jpeg",
        width = 100,
        height = 100
    )

    @Test
    fun `先読みと表示で同じリクエストになる`() {
        val image = item()
        val first = image.toViewerImageRequest(context, 1080, 2400)
        val second = image.toViewerImageRequest(context, 1080, 2400)
        assertEquals(first, second)
    }

    @Test
    fun `キャッシュキーは一覧のサムネイルと同じ規則を使う`() {
        val image = item()
        val viewer = image.toViewerImageRequest(context, 1080, 2400)
        assertEquals(GalleryImageCacheKeys.forItem(image), viewer.memoryCacheKey?.key)
        assertEquals(GalleryImageCacheKeys.forItem(image), viewer.diskCacheKey)
    }

    @Test
    fun `画像が更新されるとビューアのキャッシュキーも変わる`() {
        val before = item(modifiedSec = 100L).toViewerImageRequest(context, 1080, 2400)
        val after = item(modifiedSec = 200L).toViewerImageRequest(context, 1080, 2400)
        assertTrue(before.memoryCacheKey != after.memoryCacheKey)
    }

    @Test
    fun `サイズは表示領域に固定する`() {
        val request = item().toViewerImageRequest(context, 1080, 2400)
        // サイズを明示すると、レイアウトや画面サイズからの解決を使わない。
        // 先読みと表示で同じ指定になることが、メモリキャッシュ再利用の前提になる
        assertTrue(request.defined.sizeResolver != null)
    }

    @Test
    fun `精度は画像の原寸一致を要求しない`() {
        val request = item().toViewerImageRequest(context, 1080, 2400)
        // EXACT にすると、画面に合わせて縮小した結果が毎回サイズ不一致で捨てられる
        assertEquals(coil.size.Precision.INEXACT, request.precision)
    }

    @Test
    fun `クロスフェードは切る`() {
        // スワイプ直後は前の画像が無いため、フェードの待ちが「もたつき」に見える
        val request = item().toViewerImageRequest(context, 1080, 2400)
        assertEquals(coil.transition.Transition.Factory.NONE, request.transitionFactory)
    }

    @Test
    fun `メモリキャッシュから返ったかを判定できる値を使う`() {
        // 表示側は dataSource で先読みの効果を記録する。値の意味が変わらないことを固定する
        assertEquals("MEMORY_CACHE", DataSource.MEMORY_CACHE.name)
        assertTrue(ImageResult::class.java.isAssignableFrom(coil.request.SuccessResult::class.java))
    }
}
