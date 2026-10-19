package com.flux.tv.ui

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import com.flux.tv.App
import com.flux.tv.data.PlayItem
import com.flux.tv.data.Prefs

/**
 * Lecteur ExoPlayer réglé pour un petit boîtier Amlogic (1-2 Go de RAM) :
 * décodage matériel, mémoire tampon plafonnée, démarrage rapide des flux TS.
 */
@OptIn(UnstableApi::class)
object PlayerFactory {

    fun create(ctx: Context, preview: Boolean = false): ExoPlayer {
        // min, max, démarrage, reprise après coupure (ms), plafond mémoire (octets)
        val (minMs, maxMs, startMs, rebufferMs, bytes) = when {
            preview -> intArrayOf(2_000, 8_000, 500, 1_500, 8 shl 20)
            Prefs.bufferProfile == 0 -> intArrayOf(8_000, 30_000, 1_000, 2_000, 24 shl 20)
            Prefs.bufferProfile == 2 -> intArrayOf(30_000, 90_000, 3_000, 6_000, 64 shl 20)
            else -> intArrayOf(15_000, 50_000, 1_500, 3_000, 40 shl 20)
        }
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(minMs, maxMs, startMs, rebufferMs)
            .setTargetBufferBytes(bytes)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()

        val renderers = DefaultRenderersFactory(ctx)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)

        val extractors = DefaultExtractorsFactory()
            .setTsExtractorFlags(
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                    DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
            )
            .setConstantBitrateSeekingEnabled(true)

        val dataSource = OkHttpDataSource.Factory(App.http).setUserAgent(App.USER_AGENT)
        val mediaSources = DefaultMediaSourceFactory(dataSource, extractors)

        val trackSelector = DefaultTrackSelector(ctx)
        val params = trackSelector.buildUponParameters()
            .setTunnelingEnabled(Prefs.tunneling && !preview)
            .setViewportSizeToPhysicalDisplaySize(ctx, true)
            .setPreferredAudioLanguage(Prefs.audioLang.ifEmpty { "fr" })
        if (Prefs.subLang.isNotEmpty()) params.setPreferredTextLanguage(Prefs.subLang)
        if (preview) params.setMaxVideoSize(1280, 720)
        trackSelector.setParameters(params.build())

        return ExoPlayer.Builder(ctx, renderers)
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(mediaSources)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
    }

    fun mediaItem(p: PlayItem): MediaItem = MediaItem.Builder()
        .setUri(p.url)
        .setMediaId(p.key)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(p.title).build())
        .apply { if (p.url.contains(".m3u8")) setMimeType(MimeTypes.APPLICATION_M3U8) }
        .build()
}
