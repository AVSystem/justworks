package com.avsystem.justworks.core.gen

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.MemberName

sealed interface Package {
    val name: String
}

@JvmInline
internal value class ModelPackage(override val name: String) : Package

@JvmInline
internal value class ApiPackage(override val name: String) : Package

internal operator fun ClassName.Companion.invoke(pkg: Package, vararg simpleNames: String): ClassName =
    ClassName(pkg.name, *simpleNames)

internal operator fun MemberName.Companion.invoke(pkg: Package, memberName: String): MemberName =
    MemberName(pkg.name, memberName)
