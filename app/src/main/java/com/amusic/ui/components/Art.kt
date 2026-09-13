package com.amusic.ui.components

import android.content.Context
import coil.request.ImageRequest

/** Decode cap for artwork drawn in list rows / the mini player. */
const val ART_PX = 512

/**
 * Builds an album-art request capped at [sizePx] on each edge.
 *
 * Remote covers arrive at whatever size the CDN feels like serving — the 无损站 hands out
 * 1000px+ JPEGs. Decoding those at full resolution both wastes memory and, worse, feeds
 * very large textures to the GPU, which is where the Adreno driver abort on this device
 * came from. Capping the decode size keeps the texture small and the artwork still looks
 * sharp at every size we actually draw it (48dp rows … 300dp cover).
 */
fun artRequest(context: Context, url: String?, sizePx: Int = 512): Any? =
    url?.takeIf { it.isNotBlank() }?.let {
        ImageRequest.Builder(context)
            .data(it)
            .size(sizePx)
            .crossfade(false)
            .build()
    }
