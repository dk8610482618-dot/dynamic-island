package com.dynamicisland.engine.ingestion.media

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves album artwork for the island.
 *
 * ### Why this exists
 *
 * The tracker already reads `MediaMetadata` bitmaps, and for a self-contained
 * player that is enough. It is not enough in practice:
 *
 *  - **Most players publish a URI, not a bitmap.** Spotify, YouTube Music and
 *    others commonly set `METADATA_KEY_ALBUM_ART_URI` and leave the bitmap keys
 *    empty, because shipping a `Bitmap` across a binder transaction is expensive.
 *    Reading only the bitmap keys therefore shows a placeholder for exactly the
 *    apps users care most about.
 *  - **Some publish neither.** A locally-indexed file whose tags carry no cover
 *    has no metadata artwork at all, but the platform may still have one — from
 *    a sibling track on the same album, or a folder image.
 *
 * So resolution is a three-step fallback, cheapest and most specific first:
 *
 *  1. the player's own artwork URI,
 *  2. that URI's `MediaStore` thumbnail (for `content://` URIs, the platform
 *     generates and caches one),
 *  3. the platform's album art for the playing track.
 *
 * ### Bitmap identity
 *
 * Results are cached by URI string. The cache is small and bounded by
 * [MAX_CACHE_ENTRIES] because artwork is large and this is a service that runs
 * for days: an unbounded cache here is a slow memory leak, and the obvious
 * "cache it forever" instinct is the wrong one for a background component.
 */
internal class AlbumArtResolver(context: Context) {

    private val appContext = context.applicationContext

    /** URI string → decoded art. `Null` values are cached too, so a track with
     *  no artwork is not re-queried on every metadata update. */
    /**
     * Decoded art, keyed by URI and media id.
     *
     * The value is an [Entry] rather than a bare `Bitmap?` because
     * `ConcurrentHashMap` rejects null values outright — attempting to cache a
     * "no artwork" result throws a `NullPointerException`, which is how this
     * first shipped and how it took the whole overlay service down at startup.
     * Absence has to be represented by an object, not by null.
     */
    private val cache = ConcurrentHashMap<String, Entry>()

    /** Distinguishes "resolved to nothing" from "not yet resolved". */
    private class Entry(val bitmap: Bitmap?)

    /**
     * Decodes artwork for [artUri] (the player's own, if any) and [mediaId]
     * (the platform's id for the track, if known).
     *
     * @return the artwork, or `null` if nothing was found — in which case the
     *   caller should render its placeholder glyph rather than an empty box.
     */
    fun resolve(artUri: Uri?, mediaId: Long?): Bitmap? {
        val key = "${artUri ?: "-"}|${mediaId ?: -1L}"

        // A hit is a hit even when the cached artwork is absent — that absence is
        // the result, and re-querying for it on every metadata update would be
        // pure waste.
        cache[key]?.let { return it.bitmap }

        val bitmap = fromArtUri(artUri)
            ?: fromMediaStoreThumbnail(artUri)
            ?: fromMediaStoreAlbumArt(mediaId)

        // Bounded eviction: drop everything when the limit is reached rather
        // than tracking access order. Artwork lookups are bursty and rare, so
        // the expensive case this guards against is unbounded growth, not a
        // suboptimal hit rate.
        if (cache.size >= MAX_CACHE_ENTRIES) cache.clear()
        cache[key] = Entry(bitmap)
        return bitmap
    }

    /** Step 1: open the player's own artwork URI. */
    private fun fromArtUri(artUri: Uri?): Bitmap? {
        if (artUri == null) return null
        return runCatching {
            when (artUri.scheme?.lowercase()) {
                "http", "https" -> null // Never perform synchronous network requests on caller thread
                "content", "android.resource" -> openAndDecode(artUri)
                else -> null
            }
        }.getOrNull()
    }

    /** Manually registers resolved artwork into cache (e.g. from notification LargeIcon). */
    fun put(artUri: Uri?, mediaId: Long?, bitmap: Bitmap) {
        // `put` used to bypass the bound that `resolve` enforces, so a stream of
        // notification artworks could grow the cache without limit.
        if (cache.size >= MAX_CACHE_ENTRIES) cache.clear()
        val bounded = boundBitmap(bitmap) ?: bitmap
        // Do not poison the cache with a generic null-key entry that leaks across unrelated players
        if (artUri != null || (mediaId != null && mediaId > 0L)) {
            cache["${artUri ?: "-"}|${mediaId ?: -1L}"] = Entry(bounded)
        }
        if (artUri != null) {
            cache["$artUri|-1"] = Entry(bounded)
        }
    }

    /**
     * Step 2: ask the platform for a thumbnail of a `content://` artwork URI.
     *
     * `loadThumbnail` does the decoding and downscaling on the provider side,
     * which matters because the full-resolution artwork for a modern track is
     * several megabytes — far more than a 48 dp slot can show.
     */
    private fun fromMediaStoreThumbnail(artUri: Uri?): Bitmap? {
        if (artUri == null || artUri.scheme != "content") return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null

        return runCatching {
            appContext.contentResolver.loadThumbnail(
                artUri,
                Size(THUMBNAIL_PX, THUMBNAIL_PX),
                null
            )
        }.getOrNull()
    }

    /**
     * Step 3: the platform's own album art for the playing track.
     *
     * This is what covers a local file whose tags carry no cover image: the
     * media scanner may still have associated one, either from the album or from
     * a folder-level image.
     */
    private fun fromMediaStoreAlbumArt(mediaId: Long?): Bitmap? {
        if (mediaId == null || mediaId <= 0L) return null
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val trackUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaId)
                appContext.contentResolver.loadThumbnail(trackUri, Size(THUMBNAIL_PX, THUMBNAIL_PX), null)
            } else {
                val albumArtUri = ContentUris.withAppendedId(
                    Uri.parse("content://media/external/audio/albumart"),
                    mediaId
                )
                openAndDecode(albumArtUri)
            }
        }.getOrNull()
    }

    /** Opens a stream and decodes it, with `inSampleSize` chosen from the bounds. */
    private fun openAndDecode(uri: Uri): Bitmap? {
        val resolver = appContext.contentResolver

        // First pass reads only the header to learn the dimensions, so a large
        // image is never fully allocated just to be downscaled.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { stream: InputStream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        } ?: return null

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
            // RGB_565 halves the memory for artwork that will be drawn at 48 dp
            // and never scaled up. Alpha is irrelevant: album art is opaque.
            inPreferredConfig = Bitmap.Config.RGB_565
        }

        return resolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        }
    }

    /** Largest power-of-two downscale that keeps both sides at or above [THUMBNAIL_PX]. */
    private fun sampleSizeFor(width: Int, height: Int): Int {
        var sample = 1
        var w = width
        var h = height
        while (maxOf(w, h) / 2 >= THUMBNAIL_PX) {
            w /= 2
            h /= 2
            sample *= 2
        }
        return sample
    }

    /** Drops cached artwork. Called when memory is tight. */
    fun clear() {
        cache.clear()
        Log.d(TAG, "album art cache cleared")
    }

    companion object {
        private const val TAG = "AlbumArtResolver"

        /**
         * Decode target, in px.
         *
         * The expanded card shows artwork at 56 dp, so at 3x density that is
         * 168 px. 256 leaves headroom for the largest density in use without
         * decoding a full-size cover.
         */
        const val THUMBNAIL_PX = 256

        /** Maximum dimension for any retained or cached bitmap. */
        const val MAX_BITMAP_DIMENSION = 384

        /** Entries kept before eviction. At ~256x256 RGB_565 that is about 8 MB. */
        const val MAX_CACHE_ENTRIES = 64

        /** Hard cap on a network artwork download: 2 MiB. */
        const val MAX_HTTP_BYTES = 2 * 1024 * 1024

        /**
         * Downscales oversized bitmaps to prevent excessive memory retention and GC pressure.
         *
         * Players like Poweramp or Neutron may provide 1400x1400 or 2048x2048 bitmaps in
         * MediaMetadata, which consume 8-16MB each. Bounding them to [MAX_BITMAP_DIMENSION]
         * reduces memory consumption by >95% while maintaining crisp 56dp display.
         */
        fun boundBitmap(bitmap: Bitmap?, maxDimension: Int = MAX_BITMAP_DIMENSION): Bitmap? {
            if (bitmap == null) return null
            if (bitmap.width <= maxDimension && bitmap.height <= maxDimension) return bitmap
            val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
            val (targetW, targetH) = if (ratio > 1f) {
                maxDimension to (maxDimension / ratio).toInt().coerceAtLeast(1)
            } else {
                (maxDimension * ratio).toInt().coerceAtLeast(1) to maxDimension
            }
            return runCatching {
                Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
            }.getOrNull() ?: bitmap
        }

        /**
         * Samples an album cover [bitmap] to extract its dominant vibrant color tone as a `#RRGGBB` hex string
         * for adaptive `AudioWaveform` bars and `.keylineTint` perimeter borders.
         */
        fun extractDominantVibrantHex(bitmap: Bitmap?): String {
            if (bitmap == null || bitmap.width <= 0 || bitmap.height <= 0) return "#1DB954"
            return runCatching {
                val stepX = (bitmap.width / 8).coerceAtLeast(1)
                val stepY = (bitmap.height / 8).coerceAtLeast(1)
                var bestR = 29
                var bestG = 185
                var bestB = 84
                var bestScore = -1f
                var y = stepY / 2
                while (y < bitmap.height) {
                    var x = stepX / 2
                    while (x < bitmap.width) {
                        val pixel = bitmap.getPixel(x, y)
                        val r = (pixel shr 16) and 0xFF
                        val g = (pixel shr 8) and 0xFF
                        val b = pixel and 0xFF
                        val maxC = maxOf(r, g, b)
                        val minC = minOf(r, g, b)
                        val delta = maxC - minC
                        val luma = (r * 299 + g * 587 + b * 114) / 1000
                        // Prefer saturated, mid-to-high luminance tones that stand out against a black capsule
                        if (luma in 45..235 && delta >= 24) {
                            val saturation = delta.toFloat() / maxC.coerceAtLeast(1).toFloat()
                            val score = saturation * 100f + (1f - kotlin.math.abs(luma - 150) / 150f) * 40f
                            if (score > bestScore) {
                                bestScore = score
                                bestR = r
                                bestG = g
                                bestB = b
                            }
                        }
                        x += stepX
                    }
                    y += stepY
                }
                String.format("#%02X%02X%02X", bestR, bestG, bestB)
            }.getOrDefault("#1DB954")
        }
    }
}
