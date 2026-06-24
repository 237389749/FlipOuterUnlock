package com.example.flipunlock.hook.util

import java.lang.reflect.Field
import java.lang.reflect.Method

internal fun Class<*>.method(name: String, vararg parameterTypes: Class<*>): Method {
    classSequence().firstNotNullOfOrNull { cls ->
        runCatching {
            cls.getDeclaredMethod(name, *parameterTypes).also { it.isAccessible = true }
        }.getOrNull()
    }?.let { return it }
    throw NoSuchMethodException("${this.name}#$name")
}

internal fun Class<*>.field(name: String): Field {
    classSequence().firstNotNullOfOrNull { cls ->
        runCatching {
            cls.getDeclaredField(name).also { it.isAccessible = true }
        }.getOrNull()
    }?.let { return it }
    throw NoSuchFieldException("${this.name}#$name")
}

internal fun Any.getField(name: String): Any? = javaClass.field(name).get(this)

internal fun Any.setField(name: String, value: Any?) {
    javaClass.field(name).set(this, value)
}

internal fun Any.callMethod(name: String, vararg args: Any?): Any? {
    val parameterTypes = args.map { it?.javaClass }.toTypedArray()
    javaClass.classSequence()
        .flatMap { it.declaredMethods.asSequence() }
        .firstOrNull { candidate ->
            candidate.name == name &&
                candidate.parameterTypes.size == args.size &&
                candidate.parameterTypes.zip(parameterTypes).all { (expected, actual) ->
                    actual?.let { expected.isAssignableFrom(it) || expected.wrap() == it.wrap() } ?: true
                }
        }
        ?.let { method ->
            method.isAccessible = true
            return method.invoke(this, *args)
        }
    throw NoSuchMethodException("${javaClass.name}#$name")
}

internal fun ClassLoader.findClass(name: String): Class<*> = loadClass(name)

private fun Class<*>.classSequence(): Sequence<Class<*>> = generateSequence(this) { it.superclass }

private fun Class<*>.wrap(): Class<*> = when (this) {
    Boolean::class.javaPrimitiveType -> Boolean::class.javaObjectType
    Byte::class.javaPrimitiveType -> Byte::class.javaObjectType
    Char::class.javaPrimitiveType -> Char::class.javaObjectType
    Short::class.javaPrimitiveType -> Short::class.javaObjectType
    Int::class.javaPrimitiveType -> Int::class.javaObjectType
    Long::class.javaPrimitiveType -> Long::class.javaObjectType
    Float::class.javaPrimitiveType -> Float::class.javaObjectType
    Double::class.javaPrimitiveType -> Double::class.javaObjectType
    Void.TYPE -> Void::class.java
    else -> this
}
