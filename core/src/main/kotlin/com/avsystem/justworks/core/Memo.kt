package com.avsystem.justworks.core

import arrow.atomic.Atomic
import arrow.atomic.update

@JvmInline
value class MemoScope private constructor(private val memos: Atomic<Set<Memo<*>>>) {
    constructor() : this(Atomic(emptySet()))

    fun register(memo: Memo<*>) {
        memos.update { it + memo }
    }

    fun reset() {
        memos.get().forEach { it.reset() }
    }
}

class Memo<T>(private val compute: () -> T) {
    private val holder = Atomic(lazy(compute))

    operator fun getValue(thisRef: Any?, property: Any?): T = holder.get().value

    fun reset() {
        holder.set(lazy(compute))
    }
}

fun <T> memoized(memoScope: MemoScope, compute: () -> T): Memo<T> = Memo(compute).also(memoScope::register)
