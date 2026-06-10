package com.avsystem.justworks.core.gen

/**
 * Generation-time options that tune the produced code without affecting the
 * parsed [com.avsystem.justworks.core.model.ApiSpec] or the schema [Hierarchy].
 *
 * Threaded through the generators as a context receiver.
 */
internal data class OutputOptions(val generateKdoc: Boolean = true,)
