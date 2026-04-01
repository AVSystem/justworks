package com.avsystem.justworks.core

import kotlin.enums.enumEntries

internal const val SCHEMA_PREFIX = "#/components/schemas/"

inline fun <reified T : Enum<T>> String.toEnumOrNull(): T? = enumEntries<T>().find { it.name.equals(this, true) }
