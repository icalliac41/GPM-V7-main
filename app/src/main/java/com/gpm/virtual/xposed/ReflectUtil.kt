package com.gpm.virtual.xposed

import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method

object ReflectUtil {

    fun findClass(className: String, classLoader: ClassLoader): Class<*> =
        Class.forName(className, false, classLoader)

    fun findClassOrNull(className: String, classLoader: ClassLoader): Class<*>? =
        try { findClass(className, classLoader) } catch (_: Throwable) { null }

    fun findMethodExact(clazz: Class<*>, name: String, vararg parameterTypes: Class<*>): Method {
        val m = clazz.getDeclaredMethod(name, *parameterTypes)
        m.isAccessible = true
        return m
    }

    fun findMethodExactOrNull(clazz: Class<*>, name: String, vararg parameterTypes: Class<*>): Method? =
        try { findMethodExact(clazz, name, *parameterTypes) } catch (_: Throwable) { null }

    fun findMethodsByName(clazz: Class<*>, name: String): List<Method> =
        clazz.declaredMethods.filter { it.name == name }.onEach { it.isAccessible = true }

    fun findMethodOrNull(
        className: String,
        classLoader: ClassLoader,
        methodName: String,
        vararg parameterTypes: Class<*>
    ): Method? {
        val clazz = findClassOrNull(className, classLoader) ?: return null
        return findMethodExactOrNull(clazz, methodName, *parameterTypes)
    }

    fun findAllConstructors(clazz: Class<*>): List<Constructor<*>> =
        clazz.declaredConstructors.map { it.isAccessible = true; it }

    fun findConstructorOrNull(clazz: Class<*>, vararg parameterTypes: Class<*>): Constructor<*>? =
        try {
            val c = clazz.getDeclaredConstructor(*parameterTypes)
            c.isAccessible = true
            c
        } catch (_: Throwable) { null }

    fun getObjectField(obj: Any?, name: String): Any? {
        if (obj == null) return null
        var clazz: Class<*>? = obj.javaClass
        while (clazz != null) {
            try {
                val f = clazz.getDeclaredField(name)
                f.isAccessible = true
                return f.get(obj)
            } catch (_: NoSuchFieldException) { clazz = clazz.superclass }
        }
        return null
    }

    fun setObjectField(obj: Any?, name: String, value: Any?) {
        if (obj == null) return
        var clazz: Class<*>? = obj.javaClass
        while (clazz != null) {
            try {
                val f = clazz.getDeclaredField(name)
                f.isAccessible = true
                f.set(obj, value)
                return
            } catch (_: NoSuchFieldException) { clazz = clazz.superclass }
        }
    }

    fun findField(clazz: Class<*>, name: String): Field? {
        var c: Class<*>? = clazz
        while (c != null) {
            try {
                val f = c.getDeclaredField(name)
                f.isAccessible = true
                return f
            } catch (_: NoSuchFieldException) { c = c.superclass }
        }
        return null
    }

    fun callMethod(obj: Any?, name: String, vararg args: Any?): Any? {
        if (obj == null) return null
        val argTypes = args.map { arg ->
            when (arg) {
                null -> Any::class.java
                is Boolean -> Boolean::class.javaPrimitiveType!!
                is Int -> Int::class.javaPrimitiveType!!
                is Long -> Long::class.javaPrimitiveType!!
                is Float -> Float::class.javaPrimitiveType!!
                is Double -> Double::class.javaPrimitiveType!!
                else -> arg.javaClass
            }
        }.toTypedArray()
        var clazz: Class<*>? = obj.javaClass
        while (clazz != null) {
            try {
                val m = clazz.getDeclaredMethod(name, *argTypes)
                m.isAccessible = true
                return m.invoke(obj, *args)
            } catch (_: NoSuchMethodException) {
                val candidates = clazz.declaredMethods.filter {
                    it.name == name && it.parameterTypes.size == args.size
                }
                for (m in candidates) {
                    try {
                        m.isAccessible = true
                        return m.invoke(obj, *args)
                    } catch (_: Throwable) {}
                }
                clazz = clazz.superclass
            }
        }
        return null
    }
}