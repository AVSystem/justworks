@file:OptIn(ExperimentalContracts::class, ExperimentalRaiseAccumulateApi::class)

package com.avsystem.justworks.core

import arrow.core.Nel
import arrow.core.leftIor
import arrow.core.nonEmptyListOf
import arrow.core.raise.ExperimentalRaiseAccumulateApi
import arrow.core.raise.IorRaise
import arrow.core.raise.context.bind
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind.AT_MOST_ONCE
import kotlin.contracts.contract

object Issue {
    data class Error(val message: String)

    @JvmInline
    value class Warning(val message: String)
}

typealias Warnings = IorRaise<Nel<Issue.Warning>>
