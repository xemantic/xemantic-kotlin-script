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
import kotlin.reflect.KFunction
import kotlin.reflect.KMutableProperty
import kotlin.reflect.KParameter
import kotlin.reflect.KProperty
import kotlin.reflect.KType
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.valueParameters
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

/**
 * The class of a snippet qualifying the name of a class declared by it, like `Snippet3.` of `Snippet3.Color`, or `Snippet3$` of `Snippet3$Box`,
 * also of the element class of an array, like of `[LSnippet3$Box;`, which a later snippet does not name.
 */
internal val snippetQualifier = Regex("(?:(?<=\\[L)|(?<![\\p{L}\\p{N}_.$]))Snippet\\d+[.$](?=[\\p{L}_`])")

/**
 * The declarations of the evaluated snippets which a later snippet fails on at runtime, for the hints at them:
 * the `internal` members, see [internalMember], the `const val`s, see [constProperty],
 * and the kinds of the last properties of the names, like a `@JvmField` one, see [property].
 *
 * A later snippet resolves a name to its last declaration, so a later declaration replaces an earlier one of the same name,
 * a property of a property, a function or an extension of the same parameters, see [parameterSignature], and a class of a class,
 * also when the later one cannot be accessed, like an `internal` property or function, which a later snippet calls by a name it is not compiled to,
 * but for a `private` property or function, which a later snippet does not see at all, so it does not replace the earlier one,
 * see [visibilityToLaterSnippet].
 *
 * The classes of the snippets are kept until read, and then their names only, with the class loader of the latest one, which loads the earlier ones as well,
 * and their members read by kotlin-reflect only when a name is looked up, which is after a failure, as reading them takes long.
 * Accessed on the thread of the session only, like the snippets.
 */
internal class SnippetDeclarations {

    /** The classes of the snippets not read yet, with the names of the snippets. */
    private val pending = ArrayDeque<Pair<Class<*>, String>>()

    /** The last property of a name, not an extension. */
    private val properties = HashMap<String, SnippetProperty>()

    /** The snippets declaring a `@JvmField` property of a name, also when declared again otherwise by a later snippet. */
    private val jvmFieldProperties = HashMap<String, MutableSet<String>>()

    /** The names of the snippets by the names of their classes, like `snippet1.kts` of `Snippet1`, in the order of the snippets. */
    private val snippets = LinkedHashMap<String, String>()

    /** The class loader of the latest snippet, which loads the classes of the earlier ones as well. */
    private var classLoader: ClassLoader? = null

    /** The classes of the snippets by their JVM names, read so far, see [declaredClass]. */
    private val readClasses = HashMap<String, DeclaredClass?>()

    /** The members of the classes of the snippets by the JVM names of the classes, read so far, `null` if the snippet does not declare the class. */
    private val members = HashMap<String, Collection<KCallable<*>>?>()

    /** Keeps the [scriptClass] of the snippet of this [name], to be read once a name is looked up. */
    fun keep(scriptClass: Class<*>, name: String) {
        pending += scriptClass to name
        snippets[scriptClass.name] = name
        scriptClass.classLoader?.let { classLoader = it }
    }

    /** The name of the snippet of the class of this [name], like `snippet1.kts` of `Snippet1`, `null` if it is not the class of a snippet. */
    fun snippetOf(name: String): String? = snippets[name]

    /** The class of this [name], not initialized, as a static initializer of the class might not have run yet, or have failed. */
    private fun load(name: String): Class<*> = Class.forName(name, false, classLoader)

    /**
     * The `internal` member of a snippet, or of a class declared by it, which a later snippet failed to call
     * as the [method] of the [owner] class taking the [parameterTypes], all named like in the message of a [NoSuchMethodError],
     * like `getSecret$main` of `Snippet1` or `open` of `Snippet1$Box`,
     * as the K2 REPL compiler mangles the name of an `internal` member with the module, the snippet, like `getSecret$snippet1_kts`,
     * while a later snippet calls its accessor mangled with another module, like `getSecret$main`, and a function unmangled,
     * also by the stub of its default arguments, like `secret$default`, matched to the function by the parameter types the stub takes,
     * also a member the [owner] inherits from a superclass declared by a snippet, like `f` of `open class A { internal fun f() = 1 }` called on `class B : A()`.
     * `null` if the [owner] neither declares, nor inherits such a member, so the [method] is missing for another reason.
     *
     * @throws VirtualMachineError thrown by the reflection, other than [StackOverflowError].
     */
    fun internalMember(owner: String, method: String, parameterTypes: List<String>): InternalMember? {
        val snippet = snippets[owner.substringBefore('$')] ?: return null
        return logger.runOr(null, { "$snippet: cannot read the internal member $method of $owner" }) {
            val ownerClass = load(owner)
            generateSequence(ownerClass) { it.superclass }
                .takeWhile { it.name.substringBefore('$') in snippets }
                .firstNotNullOfOrNull { declaring -> internalMember(declaring, ownerClass, method, parameterTypes) }
        }
    }

    /** The `internal` member of the [declaring] class, the [ownerClass] or its superclass, see [internalMember]. */
    private fun internalMember(declaring: Class<*>, ownerClass: Class<*>, method: String, parameterTypes: List<String>): InternalMember? {
        val scriptClassName = declaring.name.substringBefore('$')
        val snippet = snippets.getValue(scriptClassName)
        val internalSuffix = snippet.internalSuffix()
        val defaultStub = method.endsWith(DEFAULT_STUB_SUFFIX)
        val called = method.removeSuffix(DEFAULT_STUB_SUFFIX)
        val names = setOf(called, called.substringBeforeLast('$')).map { it + internalSuffix }
        val instanceTypes = setOf(declaring.typeName, ownerClass.typeName)
        val (member, setter) = declaring.declaredMethods.asSequence().filter { it.name in names }.mapNotNull { jvmMethod ->
            val masks = if (defaultStub) {
                jvmMethod.defaultStubMasks(instanceTypes, parameterTypes) ?: return@mapNotNull null
            } else {
                if (jvmMethod.parameterTypeNames() != parameterTypes) return@mapNotNull null
                null
            }
            declaring.kotlin.members.firstNotNullOfOrNull { member ->
                when {
                    member is KProperty<*> && member.javaGetter == jvmMethod -> member to false
                    member is KMutableProperty<*> && member.javaSetter == jvmMethod -> member to true
                    member is KFunction<*> && member.javaMethod == jvmMethod -> member to false
                    else -> null
                }
            }?.takeIf { (member) ->
                // a mask per 32 value parameters, so not counting the receiver of an extension, nor the continuation of a suspend function
                masks == null || member is KFunction<*> && masks == maxOf(1, (member.valueParameters.size + Int.SIZE_BITS - 1) / Int.SIZE_BITS)
            }
        }.firstOrNull() ?: return null
        val nested = declaring.name.removePrefix(scriptClassName)
        return InternalMember(
            name = (nested.split('$').filter { it.isNotEmpty() } + member.name).joinToString("."),
            snippet = snippet,
            internalSetter = setter && (member as KProperty<*>).javaGetter?.name?.endsWith(internalSuffix) == false,
            declaringClass = declaredClass(declaring.name),
            calledOn = if (ownerClass == declaring) null else ownerClass.name.withinSnippet(),
            redeclaration = redeclaration(member, setter, scriptClassName, nested)
        )
    }

    /**
     * The `const val` of a snippet, or of an object declared by it, which a later snippet failed to read
     * by the [method] of the [owner] class taking the [parameterTypes], all named like in the message of a [NoSuchMethodError],
     * like `getLIMIT` of `Snippet1`, or `getX` of `Snippet2$O`,
     * as the K2 REPL compiler reads a `const val` of an earlier snippet by a getter, which it is not compiled with,
     * also an `internal` one, by a getter mangled with another module, like `getLIMIT$main`.
     * `null` if the [owner] declares no such property, so the [method] is missing for another reason.
     *
     * @throws VirtualMachineError thrown by the reflection, other than [StackOverflowError].
     */
    fun constProperty(owner: String, method: String, parameterTypes: List<String>): ConstProperty? {
        if (parameterTypes.isNotEmpty()) return null
        val scriptClassName = owner.substringBefore('$')
        val snippet = snippets[scriptClassName] ?: return null
        val getter = method.substringBefore('$')
        return logger.runOr(null, { "$snippet: cannot read the const property $method of $owner" }) {
            val property = load(owner).kotlin.declaredMemberProperties.firstOrNull {
                it.isConst && it.name.getterName() == getter
            } ?: return@runOr null
            val nested = owner.removePrefix(scriptClassName).split('$').filter { it.isNotEmpty() }
            ConstProperty(
                name = (nested + property.name).joinToString("."),
                snippet = snippet,
                declaringClass = declaredClass(owner)
            )
        }
    }

    /**
     * How a later snippet than the one of the [scriptClassName] declares again the [member], see [InternalMember.Redeclaration],
     * as a member of the same name, kind and parameters, see [parameterSignature], so a later snippet calls that one,
     * while the code compiled before keeps calling the `internal` one — not an overload, like `fun secret(x: Int)` of `internal fun secret()`,
     * which a later snippet does not call instead — and also its setter, if the [setter] is called.
     * A member of the snippet itself, of an empty [nested] name, is told by the last later snippet declaring it, not `private`,
     * as a `private` one is not seen by a later snippet, see [SnippetDeclarations], while another snippet does not replace it,
     * and a member of a class of the snippet, of the [nested] name, like `$Box`, by the last later snippet declaring the class,
     * which replaces the whole class, so a class not declaring the member, or declaring it `private`, does not declare it again.
     */
    private fun redeclaration(member: KCallable<*>, setter: Boolean, scriptClassName: String, nested: String): InternalMember.Redeclaration {
        val signature by lazy { member.parameterSignature() }
        val property = member is KProperty<*>
        fun KCallable<*>.declaresAgain() = name == member.name && (this is KProperty<*>) == property && parameterSignature() == signature
        val laterClasses = snippets.keys.dropWhile { it != scriptClassName }.drop(1).asReversed()
        val (declaration, snippet) = if (nested.isEmpty()) {
            laterClasses.firstNotNullOfOrNull { laterClass ->
                val snippet = snippets.getValue(laterClass)
                members(laterClass)?.lastOrNull {
                    it.declaresAgain() && it.visibilityToLaterSnippet(it.declaringSnippet(snippet)) != Visibility.PRIVATE
                }?.let { it to snippet }
            }
        } else {
            laterClasses.firstNotNullOfOrNull { laterClass -> members(laterClass + nested)?.let { it to snippets.getValue(laterClass) } }
                ?.let { (members, snippet) -> members.lastOrNull { it.declaresAgain() }?.let { it to snippet } }
        } ?: return InternalMember.Redeclaration.NONE
        val declaringSnippet = declaration.declaringSnippet(snippet)
        val visibility = declaration.visibilityToLaterSnippet(declaringSnippet)
        val setterVisibility = (declaration as? KMutableProperty<*>)?.javaSetter.visibility(declaringSnippet)
        return when {
            visibility == Visibility.ACCESSIBLE && (!setter || setterVisibility == Visibility.ACCESSIBLE) -> InternalMember.Redeclaration.PUBLIC
            visibility == Visibility.INTERNAL || setter && setterVisibility == Visibility.INTERNAL -> InternalMember.Redeclaration.INTERNAL
            // a `private` one, or a `val` of a `var` whose setter is called, or a `const val`, which a later snippet fails on otherwise
            else -> InternalMember.Redeclaration.NONE
        }
    }

    /** The snippet declaring the JVM member of this member of a class of the [snippet], which might inherit it from a class of another snippet. */
    private fun KCallable<*>.declaringSnippet(snippet: String): String =
        jvmMember()?.declaringClass?.name?.substringBefore('$')?.let { snippets[it] } ?: snippet

    /**
     * The members of the class of a snippet of this JVM [name], like `Snippet2$Box`, read once,
     * `null` if the snippet does not declare such a class, or it cannot be read, which is not kept, as no input reproducing it is known.
     */
    private fun members(name: String): Collection<KCallable<*>>? {
        if (name in members) return members[name]
        val snippet = snippets[name.substringBefore('$')] ?: return null
        return logger.runOr(null, { "$snippet: cannot read the members of $name" }) {
            val type = try {
                load(name)
            } catch (_: ClassNotFoundException) {
                members[name] = null
                return@runOr null
            }
            type.kotlin.members.also { members[name] = it }
        }
    }

    /**
     * The class declared by a snippet of this JVM [name], like `Snippet2$Box`, named within the snippet, like `Box`, see [DeclaredClass],
     * the class of the code creating a lambda or an anonymous object, as the values of that class might hold it, like `H` of `class H { val f = { 1 } }`,
     * and the enum of an enum entry with a body, which is declared again with the enum.
     * `null` for the class of a snippet itself, or a lambda of its code, or if it is not a class of a snippet, or cannot be read.
     *
     * @throws VirtualMachineError thrown by the reflection, other than [StackOverflowError].
     */
    fun declaredClass(name: String): DeclaredClass? {
        val scriptClassName = name.substringBefore('$')
        val snippet = snippets[scriptClassName] ?: return null
        if (name in readClasses) return readClasses[name]
        return logger.runOr(null, { "$snippet: cannot read the class $name" }) {
            declaredClass(load(name), scriptClassName).also { readClasses[name] = it }
        }
    }

    private fun declaredClass(type: Class<*>, scriptClassName: String): DeclaredClass? {
        if (type.name == scriptClassName) return null
        if (type.isAnonymousClass) return type.enclosingClass?.let { declaredClass(it, scriptClassName) }
        type.superclass?.takeIf { it.isEnum }?.let { return declaredClass(it, scriptClassName) }
        val kind = when {
            type.isInterface
                || type.isEnum
                || type.isLocalClass
                || type.isSynthetic
                || generateSequence(type.enclosingClass) { it.enclosingClass }.takeWhile { it.name != scriptClassName }.any { it.isInterface } ->
                DeclaredClass.Kind.OTHER
            type.kotlin.isCompanion -> DeclaredClass.Kind.COMPANION
            type.declaredFields.any { Modifier.isStatic(it.modifiers) && it.name == "INSTANCE" && it.type == type } -> DeclaredClass.Kind.OBJECT
            !Modifier.isFinal(type.modifiers) || Modifier.isAbstract(type.modifiers) -> DeclaredClass.Kind.OPEN
            else -> DeclaredClass.Kind.FINAL
        }
        return DeclaredClass(type.name.withinSnippet(), kind)
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

    /** The last property of this [name] declared by a snippet, not an extension, also a provided one, `null` if none, or only a `private` one. */
    fun property(name: String): SnippetProperty? = read().properties[name]

    /**
     * Whether the [snippet] declares a `@JvmField` property of this [name], also when a later snippet declares it again otherwise,
     * as the code compiled before keeps accessing the `@JvmField` one.
     */
    fun isJvmFieldProperty(name: String, snippet: String): Boolean = read().jvmFieldProperties[name]?.contains(snippet) == true

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
        val kClass = scriptClass.kotlin
        readVisible(snippet, "properties", { kClass.declaredMemberProperties }) { property, accessible, jvmMember ->
            val kind = logger.runOr(SnippetProperty.Kind.ACCESSIBLE, { "$snippet: cannot read the kind of ${property.name} of the snippet" }) {
                property.kind(accessible, jvmMember)
            }
            properties[property.name] = SnippetProperty(kind, snippet)
            if (kind == SnippetProperty.Kind.JVM_FIELD) jvmFieldProperties.getOrPut(property.name) { HashSet() } += snippet
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
        logger.runOr(emptyList(), { "$snippet: cannot read the $kind of the snippet" }) { members() }.forEach { member ->
            // kept if unknown, as the hint was given before the accessibility was checked
            val (visibility, jvmMember) = logger.runOr(Visibility.ACCESSIBLE to null, { "$snippet: cannot read the JVM member of ${member.name} of the snippet" }) {
                member.jvmMember().let { it.visibility(snippet) to it }
            }
            if (visibility != Visibility.PRIVATE) add(member, visibility == Visibility.ACCESSIBLE, jvmMember)
        }
    }

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
     * The visibility of this member of the [snippet] to a later snippet, told by its JVM member,
     * as kotlin-reflect reads every member of a snippet as public, the K2 REPL compiler not writing its visibility to the metadata:
     * a `private` member is private, or has no accessor, like a `private val` of a field only,
     * while an `internal` one is public, but its name is mangled with the module, the [snippet], like `getCount$snippet1_kts`,
     * which a later snippet does not call, like `getCount$main`.
     * A property is told by its getter only: one of a field only, also a public `const val` or `@JvmField` one, is `null`, neither accessible, nor internal,
     * as a later snippet fails on it wherever it reads it, see [kind].
     */
    private fun KCallable<*>.visibilityToLaterSnippet(snippet: String): Visibility? =
        jvmMember().let { if (it is Field) null else it.visibility(snippet) }

    /**
     * The JVM member telling the visibility of this member of a snippet to a later snippet, see [visibilityToLaterSnippet]:
     * of a property its getter, or, if it has none, its field, if public, of a `const val` or a `@JvmField` one.
     */
    private fun KCallable<*>.jvmMember(): Member? = when (this) {
        is KProperty<*> -> javaGetter ?: javaField?.takeIf { Modifier.isPublic(it.modifiers) }
        is KFunction<*> -> javaMethod
        else -> null
    }

    /**
     * The visibility of this JVM member of the [snippet] to a later snippet, `private` if `null`, see [visibilityToLaterSnippet].
     * The field of a `const val` or a `@JvmField` property is not mangled, so it is told accessible also when `internal`,
     * which kotlin-reflect cannot tell either, so the hints at them advise to declare them again public.
     */
    private fun Member?.visibility(snippet: String): Visibility = when {
        this == null || Modifier.isPrivate(modifiers) -> Visibility.PRIVATE
        name.endsWith(snippet.internalSuffix()) -> Visibility.INTERNAL
        else -> Visibility.ACCESSIBLE
    }

    private fun Method.parameterTypeNames(): List<String> = parameterTypes.map { it.typeName }

    /**
     * The number of the `int` masks of the given arguments taken by the stub of the default arguments of this method,
     * if it takes the [parameterTypes]: the instance, unless static, of one of the [instanceTypes], the parameters, the masks, and a marker,
     * `null` if it takes other ones.
     */
    private fun Method.defaultStubMasks(instanceTypes: Set<String>, parameterTypes: List<String>): Int? {
        val static = Modifier.isStatic(modifiers)
        if (!static && parameterTypes.firstOrNull() !in instanceTypes) return null
        val stubParameters = if (static) parameterTypes else parameterTypes.drop(1)
        val parameters = parameterTypeNames()
        if (stubParameters.size < parameters.size + 2 || stubParameters.last() != "java.lang.Object") return null
        if (stubParameters.subList(0, parameters.size) != parameters) return null
        val masks = stubParameters.subList(parameters.size, stubParameters.size - 1)
        return masks.size.takeIf { masks.all { it == "int" } }
    }

}

/** This JVM name of a class declared by a snippet, like `Snippet2$Box$Lid`, as named within the snippet, like `Box.Lid`. */
private fun String.withinSnippet(): String = substringAfter('$').replace('$', '.')

/** The JVM name of the getter of the property of this name, like `getLimit` of `limit`, or `isReady` of `isReady`. */
private fun String.getterName(): String =
    if (startsWith("is") && length > 2 && !this[2].isLowerCase()) this
    else "get" + replaceFirstChar { if (it in 'a'..'z') it.uppercaseChar() else it }

/**
 * A `const val` named within its snippet, like `LIMIT`, or `O.X` of an object, declared by the [snippet],
 * in the [declaringClass], if it is not the class of the snippet itself, see [SnippetDeclarations.constProperty].
 */
internal data class ConstProperty(val name: String, val snippet: String, val declaringClass: DeclaredClass?)

/** The suffix the compiler mangles the JVM name of an `internal` member of this snippet with, like `$snippet1_kts`. */
internal fun String.internalSuffix(): String = "$" + replace(nonIdentifierCharacter, "_")

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
 * @param declaringClass the class declaring the member within the snippet, like `Box`, `null` for a member of the snippet itself.
 * @param calledOn the subclass of the [declaringClass] the member was called on, inheriting it, like `B` of `class B : A()`,
 * `null` if called on the [declaringClass] itself.
 * @param redeclaration how a later snippet declares the member again, see [SnippetDeclarations.internalMember].
 */
internal data class InternalMember(
    val name: String,
    val snippet: String,
    val internalSetter: Boolean = false,
    val declaringClass: DeclaredClass? = null,
    val calledOn: String? = null,
    val redeclaration: Redeclaration = Redeclaration.NONE
) {

    /** How the last later snippet declaring a member of the same name, kind and parameters declares it. */
    enum class Redeclaration {

        /** Not declared again, or in a way a later snippet cannot call either, like a `val` declaring again a `var` whose setter is called. */
        NONE,

        /** Declared again accessible by a later snippet, also its setter, if called. */
        PUBLIC,

        /** Declared again `internal`, or with an `internal` setter, if called. */
        INTERNAL

    }

}

/** A class declared by a snippet, of the [name] within the snippet, like `Box` or `Box.Lid`, and of the [kind] of its values. */
internal data class DeclaredClass(val name: String, val kind: Kind) {

    enum class Kind {

        /** A final class, whose values a later snippet can create again. */
        FINAL,

        /** An open or an abstract class, whose values might be of its subclasses. */
        OPEN,

        /** An object, of a single value, which a later snippet refers to by its name. */
        OBJECT,

        /** A companion object, of a single value, which a later snippet refers to by the name of its class. */
        COMPANION,

        /** An interface, an enum, a local class, or a class nested in an interface, like `DefaultImpls`. */
        OTHER

    }

}
