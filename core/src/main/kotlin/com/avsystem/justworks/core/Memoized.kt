package com.avsystem.justworks.core

class CacheGroup private constructor(private val memoizeds: MutableSet<Memoized<*>>) {
    constructor() : this(mutableSetOf<Memoized<*>>())

    fun add(memoized: Memoized<*>) {
        memoizeds += memoized
    }

    fun reset() {
        memoizeds.forEach { it.reset() }
    }
}

class Memoized<T>(private val compute: () -> T) {
    private var value: T? = null

    operator fun getValue(thisRef: Any?, property: Any?): T = synchronized(this) {
        if (value == null) value = compute()
        value!!
    }

    fun reset() = synchronized(this) {
        value = null
    }
}

fun <T> memoized(cacheGroup: CacheGroup, compute: () -> T): Memoized<T> = Memoized(compute).also(cacheGroup::add)
