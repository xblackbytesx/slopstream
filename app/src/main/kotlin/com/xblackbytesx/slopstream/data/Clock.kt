package com.xblackbytesx.slopstream.data

import javax.inject.Inject
import javax.inject.Singleton

/** Wall clock in epoch ms, injectable so tests control "now". */
@Singleton
open class Clock @Inject constructor() {
    open fun now(): Long = System.currentTimeMillis()
}
