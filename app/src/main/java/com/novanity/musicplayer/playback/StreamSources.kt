package com.novanity.musicplayer.playback

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import com.novanity.musicplayer.appContainer
import java.io.File

/**
 * Where the players read audio from. Local songs are read straight from the device. Cloud songs
 * are streamed over HTTPS with the access key header (sent only to the user's own server) and go
 * through a disk cache, so replays and seeking back don't download again.
 */
@OptIn(UnstableApi::class)
object StreamSources {
    private const val CACHE_BYTES = 512L * 1024 * 1024
    private var cache: SimpleCache? = null

    /** One cache per process: SimpleCache doesn't allow two instances on the same folder. */
    @Synchronized
    private fun cache(context: Context): SimpleCache = cache ?: SimpleCache(
        File(context.cacheDir, "stream-cache"),
        LeastRecentlyUsedCacheEvictor(CACHE_BYTES),
        StandaloneDatabaseProvider(context),
    ).also { cache = it }

    /** HTTP with the access key added for requests to the cloud server. Also used for cover art. */
    fun httpFactory(context: Context): DataSource.Factory {
        val cloud = context.appContainer.cloud
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("VanityPlayer/Android")
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setAllowCrossProtocolRedirects(false)
        return ResolvingDataSource.Factory(http) { spec ->
            if (cloud.isOurs(spec.uri.toString())) spec.withAdditionalHeaders(cloud.authHeaders) else spec
        }
    }

    fun dataSourceFactory(context: Context): DataSource.Factory {
        val app = context.applicationContext
        val upstream = DefaultDataSource.Factory(app, httpFactory(app))
        val cached = CacheDataSource.Factory()
            .setCache(cache(app))
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        return DataSource.Factory { RoutingDataSource(remote = cached.createDataSource(), local = upstream.createDataSource()) }
    }

    fun mediaSourceFactory(context: Context): MediaSource.Factory = DefaultMediaSourceFactory(dataSourceFactory(context))

    /** Sends http(s) through the cache and everything else (content://, file://) straight through. */
    private class RoutingDataSource(private val remote: DataSource, private val local: DataSource) : DataSource {
        private var current: DataSource? = null

        override fun addTransferListener(transferListener: TransferListener) {
            remote.addTransferListener(transferListener)
            local.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val scheme = dataSpec.uri.scheme
            val source = if (scheme == "http" || scheme == "https") remote else local
            current = source
            return source.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = current!!.read(buffer, offset, length)
        override fun getUri(): Uri? = current?.uri
        override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()
        override fun close() {
            try {
                current?.close()
            } finally {
                current = null
            }
        }
    }
}
