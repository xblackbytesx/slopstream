package com.xblackbytesx.slopstream.data

import com.xblackbytesx.slopstream.core.ContentSource
import com.xblackbytesx.slopstream.core.SourceKey
import javax.inject.Inject
import javax.inject.Singleton

/** Every service the app knows, YouTube first. Sauce+ registers here later. */
@Singleton
class Sources @Inject constructor(all: Set<@JvmSuppressWildcards ContentSource>) {
    constructor(vararg all: ContentSource) : this(all.toSet())

    private val byKey: Map<SourceKey, ContentSource> = all.associateBy { it.key }

    /** In [SourceKey] order, so YouTube leads every list of services. */
    val keys: List<SourceKey> = SourceKey.entries.filter { it in byKey }

    operator fun get(key: SourceKey): ContentSource =
        byKey[key] ?: error("Source $key is not enabled")

    operator fun get(id: String): ContentSource = get(SourceKey.fromId(id))

    val youtube: ContentSource get() = get(SourceKey.YOUTUBE)
}
