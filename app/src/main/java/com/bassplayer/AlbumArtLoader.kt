package com.bassplayer

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import java.util.concurrent.Executors

object AlbumArtLoader {

    private const val CACHE_BYTES = 48 * 1024 * 1024

    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val executor = Executors.newFixedThreadPool(3)
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun key(song: Song, size: Int): String = "${song.id}@$size"

    fun peek(song: Song, size: Int): Bitmap? = cache.get(key(song, size))

    fun load(context: Context, song: Song, size: Int, callback: (Bitmap?) -> Unit) {
        val cacheKey = key(song, size)
        cache.get(cacheKey)?.let {
            callback(it)
            return
        }

        val appContext = context.applicationContext
        executor.execute {
            val bitmap = decode(appContext, song, size)
            if (bitmap != null) cache.put(cacheKey, bitmap)
            mainHandler.post { callback(bitmap) }
        }
    }

    fun bind(imageView: ImageView, song: Song, size: Int, callback: (() -> Unit)? = null) {
        imageView.tag = song.id
        showPlaceholder(imageView)
        load(imageView.context, song, size) { bitmap ->
            if (imageView.tag == song.id) showBitmap(imageView, bitmap)
            callback?.invoke()
        }
    }

    fun showPlaceholder(imageView: ImageView) {
        val pad = (imageView.resources.displayMetrics.density * 12f).toInt()
        imageView.setPadding(pad, pad, pad, pad)
        imageView.scaleType = ImageView.ScaleType.CENTER_INSIDE
        imageView.setImageResource(R.drawable.ic_music_note)
    }

    fun showBitmap(imageView: ImageView, bitmap: Bitmap?) {
        if (bitmap == null) {
            showPlaceholder(imageView)
            return
        }
        imageView.setPadding(0, 0, 0, 0)
        imageView.scaleType = ImageView.ScaleType.CENTER_CROP
        imageView.setImageBitmap(bitmap)
    }

    private fun decode(context: Context, song: Song, size: Int): Bitmap? {
        val resolver = context.contentResolver

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val bitmap = resolver.loadThumbnail(song.uri, Size(size, size), null)
                if (bitmap != null) return bitmap
            } catch (_: Exception) {
            }
        }

        if (song.albumId > 0) {
            try {
                val albumUri = ContentUris.withAppendedId(
                    Uri.parse("content://media/external/audio/albumart"),
                    song.albumId
                )
                resolver.openInputStream(albumUri)?.use { stream ->
                    val bitmap = BitmapFactory.decodeStream(stream)
                    if (bitmap != null) return scaleDown(bitmap, size)
                }
            } catch (_: Exception) {
            }
        }

        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, song.uri)
            val bytes = retriever.embeddedPicture
            retriever.release()
            if (bytes != null) {
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap != null) return scaleDown(bitmap, size)
            }
        } catch (_: Exception) {
        }

        return null
    }

    private fun scaleDown(bitmap: Bitmap, maxSize: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxSize) return bitmap
        val ratio = maxSize.toFloat() / longest
        val bitmapScaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true
        )
        if (bitmapScaled !== bitmap) bitmap.recycle()
        return bitmapScaled
    }
}
