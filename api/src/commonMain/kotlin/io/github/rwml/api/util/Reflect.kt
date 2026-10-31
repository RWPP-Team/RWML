/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.util

import kotlin.reflect.KClass

/** Reflection helpers used to reach engine members that the mappings do not expose by name. */
object Reflect {

    @Suppress("UNCHECKED_CAST")
    fun <T> get(any: Any, field: String): T? = any::class.java.getDeclaredField(field).apply {
        isAccessible = true
    }.get(any) as T?

    fun set(any: Any, field: String, value: Any?) {
        any::class.java.getDeclaredField(field).apply {
            isAccessible = true
        }.set(any, value)
    }

    @Suppress("UNCHECKED_CAST")
    inline fun <reified T, K> reifiedGet(any: T?, field: String): K? = T::class.java.getDeclaredField(field).apply {
        isAccessible = true
    }.get(any) as K?

    inline fun <reified T> reifiedSet(any: T?, field: String, value: Any?) {
        T::class.java.getDeclaredField(field).apply {
            isAccessible = true
        }.set(any, value)
    }

    inline fun <reified T : Any> callVoid(any: T?, method: String, classes: List<KClass<*>>? = null, args: List<Any?>) {
        call<T, Any>(any, method, classes, args)
    }

    inline fun <reified T, K : Any> callNotNull(
        any: T?,
        method: String,
        classes: List<KClass<*>>? = null,
        args: List<Any?>,
    ): K = call<T, K>(any, method, classes, args)!!

    @Suppress("UNCHECKED_CAST")
    inline fun <reified T, K : Any> call(
        any: T?,
        method: String,
        classes: List<KClass<*>>? = null,
        args: List<Any?>,
    ): K? = (
        classes?.let {
            T::class.java.getDeclaredMethod(
                method,
                *classes.map { it.java }.toTypedArray(),
            )
        } ?: T::class.java.declaredMethods.first { it.name == method }
        ).apply {
        isAccessible = true
    }.invoke(any, *args.toTypedArray()) as K?
}
