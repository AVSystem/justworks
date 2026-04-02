package com.avsystem.justworks.core

import kotlin.enums.enumEntries

inline fun <reified T : Enum<T>> String.toEnumOrNull(): T? = enumEntries<T>().find { it.name.equals(this, true) }
