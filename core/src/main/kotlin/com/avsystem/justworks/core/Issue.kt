@file:OptIn(ExperimentalContracts::class, ExperimentalRaiseAccumulateApi::class)

package com.avsystem.justworks.core

import arrow.core.Nel
import arrow.core.raise.ExperimentalRaiseAccumulateApi
import arrow.core.raise.IorRaise
import kotlin.contracts.ExperimentalContracts

object Issue {
    data class Error(val message: String)

    @JvmInline
    value class Warning(val message: String)
}

typealias Warnings = IorRaise<Nel<Issue.Warning>>
