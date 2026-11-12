/*
 * xemantic-kotlin-script - Kotlin script execution with LLM-friendly diagnostics
 * Copyright (C) 2026  Kazimierz Pogoda / Xemantic
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.xemantic.kotlin.script

import io.github.oshai.kotlinlogging.KotlinLogging
import java.lang.reflect.Field
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.reflect.KCallable
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KMutableProperty
import kotlin.reflect.KParameter
import kotlin.reflect.KProperty
import kotlin.reflect.KType
import kotlin.reflect.KTypeParameter
import kotlin.reflect.full.declaredMemberExtensionFunctions
import kotlin.reflect.full.declaredMemberExtensionProperties
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.extensionReceiverParameter
import kotlin.reflect.jvm.javaField
import kotlin.reflect.jvm.javaGetter
import kotlin.reflect.jvm.javaMethod
import kotlin.reflect.jvm.javaSetter

private val logger = KotlinLogging.logger {}

/** The suffix of the stub of a function taking the default arguments. */
internal const val DEFAULT_STUB_SUFFIX = "\$default"

/** A character the compiler replaces with `_` in the name of a module, like `.` of `snippet1.kts`. */
private val nonIdentifierCharacter = Regex("[^\\p{L}\\p{N}_]")

/** A name within a rendered type, like `T` or `kotlin.collections.List` of `kotlin.collections.List<T>`. */
private val typeName = Regex("[\\p{L}\\p{N}_.]+")

/** The class of a snippet qualifying the name of a type declared by it, like `Snippet1.` of `Snippet1.Box`. */
private val snippetQualifier = Regex("(?<![\\p{L}\\p{N}_.])Snippet\\d+\\.")

/**
 * The names of the declarations of the evaluated snippets which a later snippet can access,
 * for the hint at the K2 REPL compiler not resolving them within a declaration of an inferred type,
 * and of the properties a later snippet fails on wherever it reads them, a `const val` or a `@JvmField` one, see [property].
 *
 * A later snippet resolves a name to its last declaration, so a later declaration replaces an earlier one of the same name,
 * a property of a property, a function or an extension of the same parameters, see [parameterSignature], and a class of a class,
 * also when the later one cannot be accessed, like an `internal` property or function, which a later snippet calls by a name it is not compiled to,
 * failing at runtime, hinted at by [internalMember] instead, and like a `private` class,
 * but for a `private` property or function, which a later snippet does not see at all, so it does not replace the earlier one,
 * see [isAccessibleByLaterSnippet].
 *
 * The classes of the snippets are kept, and their members read by kotlin-reflect only when a name is looked up,
 * which is after a compilation error, as reading them takes long.
 * Accessed on the thread of the session only, like the snippets.
 */
internal class SnippetDeclarations {

    /** The classes of the snippets not read yet, with the names of the snippets. */
    private val pending = ArrayDeque<Pair<Class<*>, String>>()

    /** Whether the last class of a simple name is accessible by a later snippet, not `private`. */
    private val classes = HashMap<String, Boolean>()

    /** The last property of a name, not an extension. */
    private val properties = HashMap<String, SnippetProperty>()

    /** The snippets declaring a `@JvmField` property of a name, also when declared again otherwise by a later snippet. */
    private val jvmFieldProperties = HashMap<String, MutableSet<String>>()

    private val functions = HashMap<String, MutableMap<List<String>, Overload>>()

    private val extensionProperties = HashMap<String, MutableMap<List<String>, Overload>>()

    private val extensionFunctions = HashMap<String, MutableMap<List<String>, Overload>>()

    /** The classes of the snippets, with the names of the snippets, by the names of the classes, like `Snippet1`, in the order of the snippets. */
    private val snippetClasses = LinkedHashMap<String, Pair<Class<*>, String>>()

    /** Keeps the [scriptClass] of the snippet of this [name], to be read once a name is looked up. */
    fun keep(scriptClass: Class<*>, name: String) {
        pending += scriptClass to name
        snippetClasses[scriptClass.name] = scriptClass to name
    }

    /** The name of the snippet of the class of this [name], like `snippet1.kts` of `Snippet1`, `null` if it is not the class of a snippet. */
    fun snippetOf(name: String): String? = snippetClasses[name]?.second

    /**
     * The `internal` member of a snippet, or of a class declared by it, which a later snippet failed to call
     * as the [method] of the [owner] class taking the [parameterTypes], all named like in the message of a [NoSuchMethodError],
     * like `getSecret$main` of `Snippet1` or `open` of `Snippet1$Box`,
     * as the K2 REPL compiler mangles the name of an `internal` member with the module, the snippet, like `getSecret$snippet1_kts`,
     * while a later snippet calls its accessor mangled with another module, like `getSecret$main`, and a function unmangled,
     * also by the stub of its default arguments, like `secret$default`, matched to the function by the parameter types the stub takes.
     * `null` if the [owner] does not declare such a member, so the [method] is missing for another reason.
     *
     * @throws VirtualMachineError thrown by the reflection, other than [StackOverflowError].
     */
    fun internalMember(owner: String, method: String, parameterTypes: List<String>): InternalMember? {
        val (scriptClass, snippet) = snippetClasses[owner.substringBefore('$')] ?: return null
        val internalSuffix = snippet.internalSuffix()
        val defaultStub = method.endsWith(DEFAULT_STUB_SUFFIX)
        val called = method.removeSuffix(DEFAULT_STUB_SUFFIX)
        val names = setOf(called, called.substringBeforeLast('$')).map { it + internalSuffix }
        return logger.readOr(null, { "$snippet: cannot read the internal member $method of $owner" }) {
            // not initialized, as a static initializer of the class might not have run yet, or have failed
            val ownerClass = if (owner == scriptClass.name) scriptClass else Class.forName(owner, false, scriptClass.classLoader)
            ownerClass.declaredMethods.firstOrNull { declared ->
                declared.name in names && parameterTypes == if (defaultStub) declared.defaultStubParameterTypes() else declared.parameterTypeNames()
            }?.let { jvmMethod ->
                ownerClass.kotlin.members.firstNotNullOfOrNull { member ->
                    when {
                        member is KProperty<*> && member.javaGetter == jvmMethod -> member to false
                        member is KMutableProperty<*> && member.javaSetter == jvmMethod -> member to true
                        member is KFunction<*> && member.javaMethod == jvmMethod -> member to false
                        else -> null
                    }
                }
            }?.let { (member, setter) ->
                val nested = owner.removePrefix(scriptClass.name)
                val classes = nested.split('$').filter { it.isNotEmpty() }
                InternalMember(
                    name = (classes + member.name).joinToString("."),
                    snippet = snippet,
                    internalSetter = setter && (member as KProperty<*>).javaGetter?.name?.endsWith(internalSuffix) == false,
                    declaringClass = classes.joinToString(".").ifEmpty { null },
                    declaredAgain = isDeclaredAgain(member, setter, scriptClass, nested)
                )
            }
        }
    }

    /**
     * Whether the last snippet kept after the one of the [scriptClass] declaring a [member] of the same name, kind and parameters, see [parameterSignature],
     * not a `private` one, which does not replace it, in the snippet class, or in its class of the same [nested] name, like `$Box`,
     * declares it accessible by a later snippet, also its setter, if the [setter] is called,
     * so a later snippet calls that one, while the code compiled before keeps calling the `internal` one —
     * not an overload, like `fun secret(x: Int)` of `internal fun secret()`, which a later snippet does not call instead.
     */
    private fun isDeclaredAgain(member: KCallable<*>, setter: Boolean, scriptClass: Class<*>, nested: String): Boolean {
        val signature = member.parameterSignature()
        return snippetClasses.values.dropWhile { it.first != scriptClass }.drop(1).asReversed().firstNotNullOfOrNull { (laterClass, laterSnippet) ->
            logger.readOr(null, { "$laterSnippet: cannot read the members of $laterClass$nested" }) {
                val ownerClass = if (nested.isEmpty()) laterClass else try {
                    Class.forName(laterClass.name + nested, false, laterClass.classLoader)
                } catch (_: ClassNotFoundException) {
                    // the later snippet does not declare the class again
                    return@readOr null
                }
                ownerClass.kotlin.members.firstOrNull {
                    it.name == member.name
                        && (it is KProperty<*>) == (member is KProperty<*>)
                        && it.parameterSignature() == signature
                        && it.jvmMember().visibility(laterSnippet) != Visibility.PRIVATE
                }?.let {
                    it.isAccessibleByLaterSnippet(laterSnippet)
                        && (!setter || it is KMutableProperty<*> && it.javaSetter.isAccessibleByLaterSnippet(laterSnippet))
                }
            }
        } ?: false
    }

    /**
     * Whether the class of this [name], like `Snippet2$Box`, declared by a snippet, or the class of a snippet itself, has values of this class only,
     * which a later snippet can create again: a final class, not an interface, nor nested in one, like `DefaultImpls`,
     * not abstract, nor an enum, an object, or a companion object, nor a local or anonymous class.
     * `false` if it is not a class of a snippet, or cannot be read.
     *
     * @throws VirtualMachineError thrown by the reflection, other than [StackOverflowError].
     */
    fun isFinalClass(name: String): Boolean {
        val (scriptClass, snippet) = snippetClasses[name.substringBefore('$')] ?: return false
        if (name == scriptClass.name) return true
        return logger.readOr(false, { "$snippet: cannot read the class $name" }) {
            // not initialized, as a static initializer of the class might not have run yet, or have failed
            val type = Class.forName(name, false, scriptClass.classLoader)
            Modifier.isFinal(type.modifiers)
                && !Modifier.isAbstract(type.modifiers)
                && !type.isEnum
                && !type.isAnonymousClass
                && !type.isLocalClass
                && !type.isSynthetic
                && generateSequence(type) { it.enclosingClass }.takeWhile { it != scriptClass }.none { it.isInterface }
                && type.declaredFields.none { Modifier.isStatic(it.modifiers) && it.name == "INSTANCE" && it.type == type }
                && !type.kotlin.isCompanion
        }
    }

    /**
     * The kinds and the types of the parameters of this callable, but the instance, like `[VALUE Int]` of `fun secret(x: Int)`,
     * and whether they are optional, like `[VALUE Int = default]` of `fun secret(x: Int = 1)`, as a call without the argument fails otherwise,
     * with the types declared by a snippet named without the class of the snippet, like `Box` of `Snippet1.Box`,
     * so that a declaration taking a class declared again matches,
     * and the type parameters named by their positions, like `#0` of `T` of `fun <T> echo(x: T)`,
     * so that a declaration naming them otherwise, like `fun <R> echo(x: R)`, matches,
     * followed by their upper bounds, like `[VALUE #0, #0 : Number]` of `fun <T : Number> echo(x: T)`,
     * as an overload of other bounds, like `fun <R : CharSequence> echo(x: R)`, is another function.
     * Without the [optional] markers, it tells the overload a later declaration replaces, also with default arguments.
     */
    private fun KCallable<*>.parameterSignature(optional: Boolean = true): List<String> {
        val positions = typeParameters.withIndex().associate { (index, parameter) -> parameter.name to "#$index" }
        fun KType.signature() = toString().replace(snippetQualifier, "").replace(typeName) { name -> positions[name.value] ?: name.value }
        return parameters.filter {
            it.kind != KParameter.Kind.INSTANCE
        }.map {
            "${it.kind} ${it.type.signature()}${if (optional && it.isOptional) " = default" else ""}"
        } + typeParameters.mapIndexed { index, parameter ->
            "#$index : ${parameter.upperBounds.joinToString(" & ") { it.signature() }}"
        }
    }

    /** Whether the last class of this simple [name] declared by a snippet is not `private`. */
    fun isClass(name: String): Boolean = read().classes[name] == true

    /** The last property of this [name] declared by a snippet, not an extension, also a provided one, `null` if none, or only a `private` one. */
    fun property(name: String): SnippetProperty? = read().properties[name]

    /**
     * Whether the [snippet] declares a `@JvmField` property of this [name], also when a later snippet declares it again otherwise,
     * as the code compiled before keeps accessing the `@JvmField` one.
     */
    fun isJvmFieldProperty(name: String, snippet: String): Boolean = read().jvmFieldProperties[name]?.contains(snippet) == true

    /**
     * Whether a function of this [name] declared by a snippet, not an extension, is accessible by a later snippet,
     * each last overload of it, as it cannot be told which one a later snippet calls.
     */
    fun isFunction(name: String): Boolean = read().functions[name]?.values?.all { it.accessible } == true

    /** Whether a function of this [name] declared by a snippet, not an extension, also an inaccessible one, but not a `private` one, is declared. */
    fun isAnyFunction(name: String): Boolean = read().functions[name] != null

    /** The receivers of the extension properties of this [name] declared by the snippets accessible by a later snippet, in the order of the declarations. */
    fun extensionPropertyReceivers(name: String): Set<ExtensionReceiver> = read().extensionProperties.receivers(name)

    /** The receivers of the extension functions of this [name] declared by the snippets accessible by a later snippet, in the order of the declarations. */
    fun extensionFunctionReceivers(name: String): Set<ExtensionReceiver> = read().extensionFunctions.receivers(name)

    /**
     * The receivers of the last overloads of the extensions of this [name], each accessible by a later snippet,
     * so not a receiver of an inaccessible overload as well, as it cannot be told which one a later snippet calls.
     */
    private fun Map<String, Map<List<String>, Overload>>.receivers(name: String): Set<ExtensionReceiver> {
        val overloads = this[name]?.values ?: return emptySet()
        val inaccessible = overloads.filter { !it.accessible }.mapNotNullTo(HashSet()) { it.receiver }
        return overloads.filter { it.accessible }.mapNotNullTo(LinkedHashSet()) { it.receiver }.apply { removeAll(inaccessible) }
    }

    /**
     * Reads the [pending] classes, each kind of the members of a class, and each member, on its own,
     * so a member kotlin-reflect fails on loses only itself, or only its kind of the members of the class,
     * no input reproducing such a failure is known.
     * A class is removed only once read, so it is read again after a fatal error.
     *
     * @throws VirtualMachineError thrown by the reflection, other than [StackOverflowError].
     */
    private fun read(): SnippetDeclarations {
        while (pending.isNotEmpty()) {
            val (scriptClass, name) = pending.first()
            read(scriptClass, name)
            pending.removeFirst()
        }
        return this
    }

    private fun read(scriptClass: Class<*>, snippet: String) {
        logger.readOr(Unit, { "$snippet: cannot read the classes of the snippet" }) {
            scriptClass.declaredClasses.forEach { declared ->
                // the InnerClasses attribute keeps the Kotlin visibility of a nested class, but internal, which is public
                classes[declared.simpleName] = !Modifier.isPrivate(declared.modifiers)
            }
        }
        val kClass = scriptClass.kotlin
        readVisible(snippet, "properties", { kClass.declaredMemberProperties }) { property, accessible, jvmMember ->
            val kind = logger.readOr(SnippetProperty.Kind.ACCESSIBLE, { "$snippet: cannot read the kind of ${property.name} of the snippet" }) {
                property.kind(accessible, jvmMember)
            }
            properties[property.name] = SnippetProperty(kind, snippet)
            if (kind == SnippetProperty.Kind.JVM_FIELD) jvmFieldProperties.getOrPut(property.name) { HashSet() } += snippet
        }
        readVisible(snippet, "functions", { kClass.declaredMemberFunctions }) { function, accessible, _ ->
            functions.addOverload(function, accessible, receiver = null, snippet)
        }
        readVisible(snippet, "extension properties", { kClass.declaredMemberExtensionProperties }) { extension, accessible, _ ->
            extensionProperties.addOverload(extension, accessible, extension.receiver(snippet), snippet)
        }
        readVisible(snippet, "extension functions", { kClass.declaredMemberExtensionFunctions }) { extension, accessible, _ ->
            extensionFunctions.addOverload(extension, accessible, extension.receiver(snippet), snippet)
        }
    }

    /**
     * Reads the [members] of a kind of the [snippet], passing the ones a later snippet sees to [add], with whether it can access them,
     * and their [jvmMember], `null` if it cannot be read,
     * so not a `private` one, which does not replace an earlier declaration, see [SnippetDeclarations].
     */
    private inline fun readVisible(
        snippet: String,
        kind: String,
        members: () -> Collection<KCallable<*>>,
        add: (KCallable<*>, accessible: Boolean, jvmMember: Member?) -> Unit
    ) {
        logger.readOr(emptyList(), { "$snippet: cannot read the $kind of the snippet" }) { members() }.forEach { member ->
            // kept if unknown, as the hint was given before the accessibility was checked
            val (visibility, jvmMember) = logger.readOr(Visibility.ACCESSIBLE to null, { "$snippet: cannot read the JVM member of ${member.name} of the snippet" }) {
                member.jvmMember().let { it.visibility(snippet) to it }
            }
            if (visibility != Visibility.PRIVATE) add(member, visibility == Visibility.ACCESSIBLE, jvmMember)
        }
    }

    /**
     * Replaces the overload of the same parameters as this [callable], see [parameterSignature], with it, by its name,
     * kept in the place of the replaced one, or added after the others — as one of a signature of its own if the parameters cannot be read,
     * since it cannot be told then which one it replaces.
     */
    private fun MutableMap<String, MutableMap<List<String>, Overload>>.addOverload(
        callable: KCallable<*>,
        accessible: Boolean,
        receiver: ExtensionReceiver?,
        snippet: String
    ) {
        val signature = logger.readOr(null, { "$snippet: cannot read the parameters of ${callable.name} of the snippet" }) {
            callable.parameterSignature(optional = false)
        } ?: listOf("$snippet ${System.identityHashCode(callable)}")
        getOrPut(callable.name) { LinkedHashMap() }[signature] = Overload(receiver, accessible)
    }

    /** The receiver of this extension, `null` if it cannot be read, as no receiver could be matched then. */
    private fun KCallable<*>.receiver(snippet: String): ExtensionReceiver? =
        logger.readOr(null, { "$snippet: cannot read the receiver of $name of the snippet" }) {
            extensionReceiverParameter?.type?.let { ExtensionReceiver.of(it) }
        }

    /** A function or an extension of a name, of the [receiver] of an extension, if it can be read, and whether it is [accessible] by a later snippet. */
    private class Overload(val receiver: ExtensionReceiver?, val accessible: Boolean)

    private enum class Visibility { ACCESSIBLE, INTERNAL, PRIVATE }

    /**
     * The kind of this property of a snippet, not `private`, of the [jvmMember], see [jvmMember],
     * a `const val` or a `@JvmField` one told by its public field without a getter,
     * which a later snippet fails on wherever it reads it, the compiler crashing on the former, and reading the latter as an instance field, which is static.
     */
    private fun KCallable<*>.kind(accessible: Boolean, jvmMember: Member?): SnippetProperty.Kind = when {
        !accessible -> SnippetProperty.Kind.INTERNAL
        this !is KProperty<*> || jvmMember !is Field -> SnippetProperty.Kind.ACCESSIBLE
        isConst -> SnippetProperty.Kind.CONST
        else -> SnippetProperty.Kind.JVM_FIELD
    }

    /**
     * Whether a later snippet can access this member of the [snippet], told by its JVM member,
     * as kotlin-reflect reads every member of a snippet as public, the K2 REPL compiler not writing its visibility to the metadata:
     * a `private` member is private, or has no accessor, like a `private val` of a field only,
     * while an `internal` one is public, but its name is mangled with the module, the [snippet], like `getCount$snippet1_kts`,
     * which a later snippet does not call, like `getCount$main`.
     * A property is told by its getter only: one of a field only, also a public `const val` or `@JvmField` one, is not accessible,
     * as a later snippet fails on it wherever it reads it, see [kind].
     */
    private fun KCallable<*>.isAccessibleByLaterSnippet(snippet: String): Boolean =
        jvmMember().let { it !is Field && it.visibility(snippet) == Visibility.ACCESSIBLE }

    /**
     * The JVM member telling the visibility of this member of a snippet to a later snippet, see [isAccessibleByLaterSnippet]:
     * of a property its getter, or, if it has none, its field, if public, of a `const val` or a `@JvmField` one.
     */
    private fun KCallable<*>.jvmMember(): Member? = when (this) {
        is KProperty<*> -> javaGetter ?: javaField?.takeIf { Modifier.isPublic(it.modifiers) }
        is KFunction<*> -> javaMethod
        else -> null
    }

    /**
     * The visibility of this JVM member of the [snippet] to a later snippet, `private` if `null`, see [isAccessibleByLaterSnippet].
     * The field of a `const val` or a `@JvmField` property is not mangled, so it is told accessible also when `internal`,
     * which kotlin-reflect cannot tell either, so the hints at them advise to declare them again public.
     */
    private fun Member?.visibility(snippet: String): Visibility = when {
        this == null || Modifier.isPrivate(modifiers) -> Visibility.PRIVATE
        name.endsWith(snippet.internalSuffix()) -> Visibility.INTERNAL
        else -> Visibility.ACCESSIBLE
    }

    private fun Member?.isAccessibleByLaterSnippet(snippet: String): Boolean = visibility(snippet) == Visibility.ACCESSIBLE

    private fun Method.parameterTypeNames(): List<String> = parameterTypes.map { it.typeName }

    /**
     * The parameter types of the stub of the default arguments of this method:
     * the instance, unless static, the parameters, an `int` mask of the given arguments per 32 parameters, and a marker.
     */
    private fun Method.defaultStubParameterTypes(): List<String> = buildList {
        if (!Modifier.isStatic(modifiers)) add(declaringClass.typeName)
        addAll(parameterTypeNames())
        repeat(maxOf(1, (parameterCount + Int.SIZE_BITS - 1) / Int.SIZE_BITS)) { add("int") }
        add("java.lang.Object")
    }

    /** The suffix the compiler mangles the JVM name of an `internal` member of this snippet with, like `$snippet1_kts`. */
    private fun String.internalSuffix(): String = "$" + replace(nonIdentifierCharacter, "_")

}

/** A property of the [snippet], not an extension, of the [kind] telling whether a later snippet can access it. */
internal data class SnippetProperty(val kind: Kind, val snippet: String) {

    enum class Kind {

        /** Accessible by a later snippet, when it resolves it, see [SnippetDeclarations]. */
        ACCESSIBLE,

        /** An `internal` property, which a later snippet calls by a name it is not compiled to, see [SnippetDeclarations.internalMember]. */
        INTERNAL,

        /** A `const val`, which the compiler crashes on wherever a later snippet reads it. */
        CONST,

        /** A `@JvmField` property, which a later snippet accesses as an instance field, while it is static. */
        JVM_FIELD

    }

}

/**
 * An `internal` member of the [snippet], named like `secret`, or qualified by the classes declaring it within the snippet, like `Box.open`,
 * or a property of an [internalSetter] only, like `var count = 0; internal set`.
 *
 * @param declaringClass the classes declaring the member within the snippet, like `Box`, `null` for a member of the snippet itself.
 * @param declaredAgain whether a later snippet declares a member of the same name accessible by a later snippet, see [SnippetDeclarations.internalMember].
 */
internal data class InternalMember(
    val name: String,
    val snippet: String,
    val internalSetter: Boolean = false,
    val declaringClass: String? = null,
    val declaredAgain: Boolean = false
)

/**
 * The receiver of an extension declared by a snippet, of the simple [name] of its class, like `String` or `Entry`,
 * or of the upper bound of a type parameter, `Any` if unbounded, taking `null` if [nullable].
 */
internal data class ExtensionReceiver(
    val name: String,
    val nullable: Boolean
) {

    /**
     * Whether an extension of this receiver can be called on the receiver of this [type], as rendered by a compiler message,
     * like `List<Int>`, `Map.Entry<Int, Int>` or `String?`, matched by the simple name of its class only,
     * so not on a subtype, like `MutableList<Int>` of `List`, which cannot be told by the name.
     */
    fun accepts(type: String): Boolean {
        val typeNullable = type.endsWith("?")
        val typeName = type.removeSuffix("?").removeSuffix("!").substringBefore('<').substringAfterLast('.')
        return (name == "Any" || name == typeName) && (nullable || !typeNullable)
    }

    override fun toString(): String = if (nullable) "$name?" else name

    companion object {

        /** The receiver of this [type], `null` if it is not a class, nor a type parameter of a single bound of a class, like a function type. */
        fun of(type: KType): ExtensionReceiver? = when (val classifier = type.classifier) {
            is KClass<*> -> classifier.simpleName?.let { ExtensionReceiver(it, type.isMarkedNullable) }
            is KTypeParameter -> classifier.upperBounds.singleOrNull()?.let { bound ->
                of(bound)?.let { it.copy(nullable = it.nullable || type.isMarkedNullable) }
            }
            else -> null
        }

    }

}
