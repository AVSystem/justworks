package com.avsystem.justworks.core

import arrow.core.Nel
import arrow.core.nonEmptyListOf
import arrow.core.raise.IorRaise
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind.AT_MOST_ONCE
import kotlin.contracts.contract

@OptIn(ExperimentalContracts::class)
context(iorRaise: IorRaise<Nel<Error>>)
inline fun <Error> ensureOrAccumulate(condition: Boolean, error: () -> Error) {
    contract { callsInPlace(error, AT_MOST_ONCE) }
    if (!condition) {
        iorRaise.accumulate(nonEmptyListOf(error()))
    }
}

@OptIn(ExperimentalContracts::class)
context(iorRaise: IorRaise<Nel<Error>>)
inline fun <Error, B : Any> ensureNotNullOrAccumulate(value: B?, error: () -> Error) {
    contract { callsInPlace(error, AT_MOST_ONCE) }
    if (value == null) {
        iorRaise.accumulate(nonEmptyListOf(error()))
    }
}

context(iorRaise: IorRaise<Nel<Error>>)
fun <Error> accumulate(error: Error): Nothing? {
    iorRaise.accumulate(nonEmptyListOf(error))
    return null
}
