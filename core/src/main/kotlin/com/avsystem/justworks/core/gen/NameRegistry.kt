package com.avsystem.justworks.core.gen

/**
 * Registry that tracks used names and resolves collisions with numeric suffixes.
 *
 * When a desired name is already taken, appends incrementing numeric suffixes
 * (e.g. `Foo`, `Foo2`, `Foo3`). Names can be pre-populated via [reserve] to
 * block them from being returned by [register].
 */
class NameRegistry {
    private val registered = mutableSetOf<String>()

    /**
     * Registers [desired] name, returning it if available or appending a numeric suffix
     * to resolve collisions (e.g. `Foo2`, `Foo3`).
     */
    fun register(desired: String): String {
        if (registered.add(desired)) return desired
        var suffix = 2
        while (!registered.add("$desired$suffix")) suffix++
        return "$desired$suffix"
    }

    /**
     * Reserves [name] so that subsequent [register] calls for the same name
     * will receive a suffixed variant.
     */
    fun reserve(name: String) {
        registered.add(name)
    }
}
