@file:OptIn(ExperimentalRaiseAccumulateApi::class)

package com.avsystem.justworks.core

import arrow.core.Nel
import arrow.core.raise.ExperimentalRaiseAccumulateApi
import arrow.core.raise.IorRaise

object Issue {
    data class Error(val message: String)

    @JvmInline
    value class Warning(val message: String)
}

typealias Warnings = IorRaise<Nel<Issue.Warning>>
