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
import kotlin.reflect.KTypeProjection
import kotlin.reflect.KTypeParameter
import kotlin.reflect.KVariance
import kotlin.reflect.full.allSupertypes
import kotlin.reflect.full.declaredMemberExtensionFunctions
import kotlin.reflect.full.declaredMemberExtensionProperties
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.extensionReceiverParameter
import kotlin.reflect.full.valueParameters
import kotlin.reflect.full.withNullability
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

/** The JVM name of a class declared by a snippet, like `Snippet1$Box`. */
private val snippetClassName = Regex("Snippet\\d+\\$.*")

/**
 * The class of a snippet qualifying the name of a class declared by it, like `Snippet3.` of `Snippet3.Color`, or `Snippet3$` of `Snippet3$Box`,
 * also of the element class of an array, like of `[LSnippet3$Box;`, which a later snippet does not name.
 */
internal val snippetQualifier = Regex("(?:(?<=\\[L)|(?<![\\p{L}\\p{N}_.$]))Snippet\\d+[.$](?=[\\p{L}_`])")

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
 * see [visibilityToLaterSnippet].
 *
 * The classes of the snippets are kept until read, and then their names only, with the class loader of the latest one, which loads the earlier ones as well,
 * and their members read by kotlin-reflect only when a name is looked up, which is after a compilation error, as reading them takes long.
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

    /** The number of the snippets read so far, the index of the next one, see [Overload.snippetIndex]. */
    private var readSnippets = 0

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
     * The last overloads of the extension properties of this [name] declared by the snippets inaccessible by a later snippet,
     * as a later snippet might call such an extension rather than an accessible one, failing then,
     * like `internal val String.p` rather than `val CharSequence.p` on a `String`.
     */
    fun inaccessibleExtensionProperties(name: String): List<SnippetExtension> = read().extensionProperties.extensions(name, accessible = false)

    /** The last overloads of the extension functions of this [name] inaccessible by a later snippet, see [inaccessibleExtensionProperties]. */
    fun inaccessibleExtensionFunctions(name: String): List<SnippetExtension> = read().extensionFunctions.extensions(name, accessible = false)

    /** The last overloads of the extension properties of this [name] accessible by a later snippet, of the receivers of [extensionPropertyReceivers]. */
    fun accessibleExtensionProperties(name: String): List<SnippetExtension> = read().extensionProperties.extensions(name, accessible = true)

    /** The last overloads of the extension functions of this [name] accessible by a later snippet, of the receivers of [extensionFunctionReceivers]. */
    fun accessibleExtensionFunctions(name: String): List<SnippetExtension> = read().extensionFunctions.extensions(name, accessible = true)

    private fun Map<String, Map<List<String>, Overload>>.extensions(name: String, accessible: Boolean): List<SnippetExtension> {
        val overloads = this[name]?.values.orEmpty()
        val receivers = if (accessible) receivers(name) else emptySet()
        return overloads.filter { it.accessible == accessible && (!accessible || it.receiver in receivers) }
            .map { SnippetExtension(it.receiver, it.receiverClass, it.snippetIndex) }
    }

    /**
     * The class of this name of a type, as rendered by a compiler message, like `List`, `IntRange` or `Snippet1.Point`,
     * a class of a snippet, a built-in class, or a class of a package imported by default, or of a type alias of one, like `ArrayList`,
     * `null` if not known, like `BigDecimal`, as a compiler message renders a type of another package without it, so it could be of any.
     */
    fun classOf(name: String): KClass<*>? {
        if (!name.matches(className)) return null
        builtInClasses[name]?.let { return it }
        snippetQualifier.find(name)?.takeIf { it.range.first == 0 }?.let { qualifier ->
            val scriptClassName = qualifier.value.dropLast(1)
            if (scriptClassName !in snippets) return null
            return loadClass(scriptClassName + "$" + name.substring(qualifier.range.last + 1).replace('.', '$'), classLoader ?: return null)
        }
        val loader = classLoader ?: SnippetDeclarations::class.java.classLoader
        val binaryName = name.replace('.', '$')
        aliasedClasses[name]?.let { return loadClass(it, loader) }
        return defaultPackages.firstNotNullOfOrNull { loadClass(it + binaryName, loader) }
    }

    private fun loadClass(name: String, classLoader: ClassLoader): KClass<*>? = try {
        // not initialized, as a static initializer of the class might not have run yet, or fail
        Class.forName(name, false, classLoader).kotlin
    } catch (_: ClassNotFoundException) {
        null
    } catch (_: LinkageError) {
        null
    }

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
        val snippetIndex = readSnippets++
        logger.runOr(Unit, { "$snippet: cannot read the classes of the snippet" }) {
            scriptClass.declaredClasses.forEach { declared ->
                // the InnerClasses attribute keeps the Kotlin visibility of a nested class, but internal, which is public
                classes[declared.simpleName] = !Modifier.isPrivate(declared.modifiers)
            }
        }
        val kClass = scriptClass.kotlin
        readVisible(snippet, "properties", { kClass.declaredMemberProperties }) { property, accessible, jvmMember ->
            val kind = logger.runOr(SnippetProperty.Kind.ACCESSIBLE, { "$snippet: cannot read the kind of ${property.name} of the snippet" }) {
                property.kind(accessible, jvmMember)
            }
            properties[property.name] = SnippetProperty(kind, snippet)
            if (kind == SnippetProperty.Kind.JVM_FIELD) jvmFieldProperties.getOrPut(property.name) { HashSet() } += snippet
        }
        readVisible(snippet, "functions", { kClass.declaredMemberFunctions }) { function, accessible, _ ->
            functions.addOverload(function, accessible, receiver = null, receiverClass = null, snippet, snippetIndex)
        }
        readVisible(snippet, "extension properties", { kClass.declaredMemberExtensionProperties }) { extension, accessible, _ ->
            extensionProperties.addOverload(extension, accessible, extension.receiver(snippet), extension.receiverClass(snippet), snippet, snippetIndex)
        }
        readVisible(snippet, "extension functions", { kClass.declaredMemberExtensionFunctions }) { extension, accessible, _ ->
            extensionFunctions.addOverload(extension, accessible, extension.receiver(snippet), extension.receiverClass(snippet), snippet, snippetIndex)
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

    /**
     * Replaces the overload of the same parameters as this [callable], see [parameterSignature], with it, by its name,
     * kept in the place of the replaced one, or added after the others — as one of a signature of its own if the parameters cannot be read,
     * since it cannot be told then which one it replaces.
     */
    private fun MutableMap<String, MutableMap<List<String>, Overload>>.addOverload(
        callable: KCallable<*>,
        accessible: Boolean,
        receiver: ExtensionReceiver?,
        receiverClass: KClass<*>?,
        snippet: String,
        snippetIndex: Int
    ) {
        val signature = logger.runOr(null, { "$snippet: cannot read the parameters of ${callable.name} of the snippet" }) {
            callable.parameterSignature(optional = false)
        } ?: listOf("$snippet ${System.identityHashCode(callable)}")
        getOrPut(callable.name) { LinkedHashMap() }[signature] = Overload(receiver, receiverClass, accessible, snippetIndex)
    }

    /** The receiver of this extension, `null` if it cannot be read, as no receiver could be matched then. */
    private fun KCallable<*>.receiver(snippet: String): ExtensionReceiver? =
        logger.runOr(null, { "$snippet: cannot read the receiver of $name of the snippet" }) {
            extensionReceiverParameter?.type?.let { ExtensionReceiver.of(it) }
        }

    /**
     * The class of the receiver of this extension, or of the bound of its type parameter, `Any` if unbounded,
     * `null` if it cannot be read, or the bound is not a single class.
     */
    private fun KCallable<*>.receiverClass(snippet: String): KClass<*>? =
        logger.runOr(null, { "$snippet: cannot read the receiver class of $name of the snippet" }) {
            when (val classifier = extensionReceiverParameter?.type?.classifier) {
                is KClass<*> -> classifier
                is KTypeParameter -> classifier.upperBounds.singleOrNull()?.classifier as? KClass<*>
                else -> null
            }
        }

    /**
     * A function or an extension of a name, of the [receiver] of an extension, if it can be read, and of its [receiverClass],
     * whether it is [accessible] by a later snippet, and the [snippetIndex] of the snippet declaring it, in the order of the snippets.
     */
    private class Overload(val receiver: ExtensionReceiver?, val receiverClass: KClass<*>?, val accessible: Boolean, val snippetIndex: Int)

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

/**
 * An extension of the [receiver], `null` if it cannot be read, of the [receiverClass], see [ExtensionReceiver.mightAccept],
 * declared by a snippet of the [snippetIndex], in the order of the snippets, as the compiler calls an extension of a later snippet first.
 */
internal class SnippetExtension(val receiver: ExtensionReceiver?, val receiverClass: KClass<*>?, val snippetIndex: Int)

/** The classes of the built-in types, by their names, which share their JVM classes, like `List` and `MutableList`, or have none, like `Int`. */
private val builtInClasses: Map<String, KClass<*>> = mapOf(
    "Any" to Any::class, "Nothing" to Nothing::class, "Unit" to Unit::class,
    "String" to String::class, "CharSequence" to CharSequence::class, "Comparable" to Comparable::class, "Number" to Number::class,
    "Int" to Int::class, "Long" to Long::class, "Short" to Short::class, "Byte" to Byte::class,
    "Double" to Double::class, "Float" to Float::class, "Char" to Char::class, "Boolean" to Boolean::class,
    "Iterable" to Iterable::class, "MutableIterable" to MutableIterable::class, "Collection" to Collection::class, "MutableCollection" to MutableCollection::class,
    "List" to List::class, "MutableList" to MutableList::class, "Set" to Set::class, "MutableSet" to MutableSet::class,
    "Map" to Map::class, "MutableMap" to MutableMap::class, "Map.Entry" to Map.Entry::class, "MutableMap.MutableEntry" to MutableMap.MutableEntry::class,
    "Iterator" to Iterator::class, "MutableIterator" to MutableIterator::class, "Throwable" to Throwable::class
)

/** The classes of the type aliases of the standard library to a class of a package not imported by default, by the names of the aliases. */
private val aliasedClasses = listOf("ArrayList", "HashMap", "HashSet", "LinkedHashMap", "LinkedHashSet", "RandomAccess").associateWith { "java.util.$it" }

/** The packages imported by default, which a compiler message renders a type of without its package. */
private val defaultPackages = listOf(
    "kotlin.", "kotlin.annotation.", "kotlin.collections.", "kotlin.comparisons.", "kotlin.io.", "kotlin.ranges.", "kotlin.sequences.", "kotlin.text.",
    "kotlin.jvm.", "java.lang."
)

/** This JVM name of a class declared by a snippet, like `Snippet2$Box$Lid`, as named within the snippet, like `Box.Lid`. */
private fun String.withinSnippet(): String = substringAfter('$').replace('$', '.')

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

/**
 * A qualifier of a rendered type, a package, like `kotlin.collections.` of `kotlin.collections.Map.Entry`,
 * or the class of a snippet, see [snippetQualifier], which a later snippet does not name.
 */
private val qualifier = Regex("(?<![\\p{L}\\p{N}_.])(?:[a-z_][\\p{L}\\p{N}_]*\\.)+|${snippetQualifier.pattern}")

/** The expansion of a type alias, as kotlin-reflect renders it after the alias, like ` /* = java.lang.StringBuilder */`. */
private const val ALIAS_EXPANSION_START = " /* = "

private const val ALIAS_EXPANSION_END = " */"

/** The name of a class, also of a nested one, like `Map.Entry`, unlike a function type, like `(Int) -> Int`. */
private val className = Regex("[\\p{L}\\p{N}_.]+")

/**
 * The supertypes of the well-known types of `kotlin.*`, and of the JVM types of the collection functions, like `ArrayList` of `arrayListOf`,
 * by the names a compiler message renders them with,
 * a read-only type not a subtype of the mutable one, like `List` of `MutableList`, unlike their JVM classes.
 * A subtype not listed, like a class of a snippet, is not accepted, as its supertypes cannot be told by its name.
 */
private val wellKnownSupertypes: Map<String, Set<String>> = buildMap {
    put("String", setOf("CharSequence", "Comparable", "Serializable"))
    listOf("Char", "Boolean").forEach { put(it, setOf("Comparable", "Serializable")) }
    listOf("Byte", "Short", "Int", "Long", "Float", "Double").forEach { put(it, setOf("Number", "Comparable", "Serializable")) }
    put("Collection", setOf("Iterable"))
    put("List", setOf("Collection", "Iterable"))
    put("Set", setOf("Collection", "Iterable"))
    put("MutableIterable", setOf("Iterable"))
    put("MutableCollection", setOf("Collection", "MutableIterable", "Iterable"))
    put("MutableList", setOf("List", "MutableCollection", "Collection", "MutableIterable", "Iterable"))
    put("MutableSet", setOf("Set", "MutableCollection", "Collection", "MutableIterable", "Iterable"))
    put("MutableMap", setOf("Map"))
    put("MutableMap.MutableEntry", setOf("Map.Entry"))
    put("ArrayList", getValue("MutableList") + "MutableList")
    put("HashSet", getValue("MutableSet") + "MutableSet")
    put("LinkedHashSet", getValue("HashSet") + "HashSet")
    put("HashMap", getValue("MutableMap") + "MutableMap")
    put("LinkedHashMap", getValue("HashMap") + "HashMap")
    put("StringBuilder", setOf("CharSequence", "Appendable"))
}

/**
 * The final types of [wellKnownSupertypes] listing each of their supertypes named there,
 * so not a subtype of another type named there, unlike `StringBuilder`, which is `Comparable` only since Java 11.
 */
private val closedWellKnownTypes = setOf("String", "Char", "Boolean", "Byte", "Short", "Int", "Long", "Float", "Double")

/**
 * The mutable collection types of invariant type parameters, which kotlin-reflect reads as the read-only ones of covariant type parameters,
 * like `MutableList` as `List`, as they share their JVM class.
 */
private val invariantMutableTypes = setOf(
    "MutableCollection", "MutableList", "MutableSet", "MutableMap", "MutableMap.MutableEntry", "MutableListIterator"
)

/** The names of the types of [wellKnownSupertypes], the subtypes and the supertypes. */
private val wellKnownTypes = wellKnownSupertypes.keys + wellKnownSupertypes.values.flatten()

/** Whether an extension can be called on a receiver of a type, see [ExtensionReceiver.acceptance]. */
internal enum class Acceptance {

    /** It cannot. */
    REJECTED,

    /** It might, which is not told by the rendering of the type, like `List<Text>` of `List<CharSequence>`, `Text` declared by a later snippet. */
    POSSIBLE,

    /** It can. */
    ACCEPTED

}

/**
 * The receiver of an extension declared by a snippet, of the [name] of its class without the package, like `String` or `Map.Entry`,
 * with its [typeArguments], `*` for a star projection, matching any, or `null` for one of a type parameter,
 * matching any unless [constrained][constrainedArguments],
 * or of a function type, like `(Int) -> Int` or `List<Int>.() -> Int`, or of the upper bound of a type parameter, `Any` if unbounded,
 * taking `null` if [nullable].
 *
 * @param variances the variance of each of the [typeArguments], of its projection, like `out Number`, or of the type parameter of its class,
 * like `out E` of `List`, `null` if not known, [KVariance.INVARIANT] if not given.
 * @param constrainedArguments the indices of the [typeArguments] of a type parameter which do not match any type argument,
 * as it has a bound, like `T` of `fun <T : Number> List<T>.f()`, or is used again, like `K` of `fun <K> Map<K, K>.f()`,
 * or is nullable, or nested, like `List<T>` of `fun <T> List<List<T>>.f()`, which are not checked, so [possibly][Acceptance.POSSIBLE] matching.
 */
internal data class ExtensionReceiver(
    val name: String,
    val typeArguments: List<String?> = emptyList(),
    val nullable: Boolean = false,
    val variances: List<KVariance?> = emptyList(),
    val constrainedArguments: Set<Int> = emptySet()
) {

    /**
     * The receiver as declared by an extension, like `Map.Entry<String, Int>` or `((Int) -> Int)?`,
     * `null` if a type argument is not known, as a type parameter is not declared by an extension of a later snippet.
     */
    val declaration: String? get() = when {
        typeArguments.any { it == null } -> null
        name.matches(className) -> toString()
        else -> if (nullable) "($name)?" else "($name)"
    }

    /**
     * Whether an extension of this receiver can be called on the receiver of a type, as rendered by a compiler [message],
     * like `List<Int>`, `Map.Entry<Int, Int>`, `String?` or `Snippet1.Point`, a class of a snippet named by the class of the snippet,
     * of the same class, or of a well-known subtype, see [wellKnownSupertypes], or by any type of an intersection type, like `Comparable<*> & Serializable`,
     * and of type arguments accepted by the ones of this receiver, see [argumentAcceptance], if of as many,
     * or only [possibly][Acceptance.POSSIBLE] if of other ones, like `Comparable<String>` of `String`.
     * Another class is not accepted, as a subtype not well-known cannot be told by its name,
     * unless the receiver is [nested] as a covariant type argument, where it is only rejected if it is surely not a subtype,
     * a class of [closedWellKnownTypes] by another class of [wellKnownTypes].
     */
    fun acceptance(message: String, nested: Boolean = false): Acceptance {
        val type = message.replace(snippetQualifier, "")
        val intersection = type.splitAtTopLevel(" & ")
        if (intersection.size > 1) return intersection.maxOf { acceptance(it, nested) }
        val (base, typeNullable) = type.withoutNullability()
        if (typeNullable && !nullable) return Acceptance.REJECTED
        if (name == "Any") return Acceptance.ACCEPTED
        val typeName = base.substringBefore('<')
        val otherClass = if (nested && !(typeName in closedWellKnownTypes && name in wellKnownTypes)) Acceptance.POSSIBLE else Acceptance.REJECTED
        if (base.isFunctionType() || !typeName.matches(className)) return if (base == name) Acceptance.ACCEPTED else otherClass
        val arguments = typeArgumentsOf(base)
        return when (name) {
            typeName -> argumentsAcceptance(arguments)
            in wellKnownSupertypes[typeName].orEmpty() -> when {
                arguments.size == typeArguments.size -> argumentsAcceptance(arguments)
                typeArguments.indices.all { typeArguments[it] == "*" || typeArguments[it] == null && it !in constrainedArguments } ->
                    Acceptance.ACCEPTED
                else -> Acceptance.POSSIBLE
            }
            else -> otherClass
        }
    }

    /**
     * Whether an extension of this receiver, of the [receiverClass], might be called on the receiver of a type, as rendered by a compiler [message],
     * unless it surely cannot: a type of a known class, see [classOf], is a subtype of the classes of its supertypes only,
     * as kotlin-reflect reads them like the compiler, like `Serializable` of `String`, but not `Constable`,
     * and of the same class only of the type arguments it accepts, see [acceptance],
     * while a type of a class not known is not a subtype of a class of a snippet, unless of a snippet itself,
     * nor of another final class, told by its name.
     */
    fun mightAccept(message: String, receiverClass: KClass<*>?, classOf: (String) -> KClass<*>?): Boolean {
        val (type, typeNullable) = message.withoutNullability()
        if (typeNullable && !nullable) return false
        if (receiverClass == null) return true
        return type.splitAtTopLevel(" & ").any { part ->
            val typeName = part.removeSuffix("!").substringBefore('<')
            val typeClass = classOf(typeName)
            when {
                typeClass == receiverClass -> acceptance(part) != Acceptance.REJECTED
                typeClass != null -> typeClass.allSupertypes.any { it.classifier == receiverClass }
                snippetClassName.matches(receiverClass.java.name) -> snippetQualifier.find(typeName)?.range?.first == 0
                receiverClass.isFinal -> typeName.substringAfterLast('.') == receiverClass.simpleName
                else -> true
            }
        }
    }

    /**
     * Whether this receiver is surely less specific than the [other] one, both accepting a type, so the compiler calls an extension of the [other] one:
     * of `Any`, or of a type parameter, which is of its bound, see [of], or of a well-known supertype of the [other] one, see [wellKnownSupertypes],
     * or nullable, if the [other] one is not.
     */
    fun isLessSpecificThan(other: ExtensionReceiver): Boolean = when {
        other.nullable && !nullable -> false
        name == "Any" -> other.name != "Any" || nullable && !other.nullable
        name == other.name && typeArguments == other.typeArguments -> nullable && !other.nullable
        else -> name in wellKnownSupertypes[other.name].orEmpty()
    }

    private fun argumentsAcceptance(arguments: List<String>): Acceptance =
        if (arguments.size != typeArguments.size) Acceptance.REJECTED else typeArguments.indices.minOfOrNull { index ->
            val expected = typeArguments[index]
            if (expected == null && index in constrainedArguments) Acceptance.POSSIBLE
            else argumentAcceptance(expected, arguments[index], variances.getOrElse(index) { KVariance.INVARIANT })
        } ?: Acceptance.ACCEPTED

    /**
     * Whether the [expected] type argument of this receiver, of the [variance] of the type parameter of its class, accepts the [actual] one:
     * the same, or, if covariant, like `out E` of `List`, or projected `out`, like `MutableList<out Number>`, its subtype,
     * like `Int` of `Number`, see [acceptance], or only possibly if contravariant, or the variance is not known.
     */
    private fun argumentAcceptance(expected: String?, actual: String, variance: KVariance?): Acceptance {
        if (expected == null || expected == "*") return Acceptance.ACCEPTED
        val (projection, type) = when {
            expected.startsWith("out ") -> KVariance.OUT to expected.removePrefix("out ")
            expected.startsWith("in ") -> KVariance.IN to expected.removePrefix("in ")
            else -> variance to expected
        }
        // a platform type, like `String!`, is also of its nullable and non-null type
        val argument = actual.removeSuffix("!")
        if (expected == argument || type == argument) return Acceptance.ACCEPTED
        return when (projection) {
            KVariance.INVARIANT -> Acceptance.REJECTED
            KVariance.OUT -> receiverOf(type).acceptance(
                when {
                    argument == "*" -> "Any?"
                    argument.startsWith("out ") -> argument.removePrefix("out ")
                    else -> argument
                },
                nested = true
            )
            else -> Acceptance.POSSIBLE
        }
    }

    override fun toString(): String {
        val arguments = if (typeArguments.isEmpty()) "" else typeArguments.joinToString(", ", "<", ">") { it ?: "*" }
        val type = name + arguments
        return when {
            !nullable -> type
            name.matches(className) -> "$type?"
            else -> "($type)?"
        }
    }

    companion object {

        /**
         * The receiver of this [type], `null` if it is not a class, nor a type parameter of a single bound of a class,
         * nor a function type of no type parameter, like `(T) -> Unit`, which could not be matched by its rendering.
         */
        fun of(type: KType): ExtensionReceiver? = when (val classifier = type.classifier) {
            is KClass<*> -> {
                val rendered = type.withNullability(false).toString().unqualified()
                val name = rendered.substringBefore('<')
                when {
                    // not matched if a type alias cannot be expanded, or its type arguments are not the ones of the expansion
                    ALIAS_EXPANSION_START.trim() in rendered -> null
                    type.hasTypeParameter() && (rendered.isFunctionType() || !name.matches(className)) -> null
                    // like `List<Int>.() -> Int`, of a function class, `Function1`, but not of its type arguments
                    rendered.isFunctionType() || !name.matches(className) -> ExtensionReceiver(rendered, nullable = type.isMarkedNullable)
                    else -> type.arguments.takeIf { it.size == typeArgumentsOf(rendered).size }?.let { arguments ->
                        ExtensionReceiver(
                            name = name,
                            typeArguments = arguments.map { it.render() },
                            nullable = type.isMarkedNullable,
                            variances = arguments.mapIndexed { index, argument ->
                                when (argument.variance) {
                                    // a mutable collection type shares its class with the read-only one, like `List` of `MutableList`, but its type parameters are invariant
                                    KVariance.INVARIANT -> if (name in invariantMutableTypes) KVariance.INVARIANT else classifier.typeParameters.getOrNull(index)?.variance
                                    else -> argument.variance
                                }
                            },
                            constrainedArguments = type.constrainedArguments()
                        )
                    }
                }
            }
            is KTypeParameter -> classifier.upperBounds.singleOrNull()?.let { bound ->
                of(bound)?.let { it.copy(nullable = it.nullable || type.isMarkedNullable) }
            }
            else -> null
        }

        /**
         * The receiver of this rendered [type], a type argument of a receiver, like `Collection<Int>` or `(Int) -> Int`,
         * of type arguments of variances not known.
         */
        private fun receiverOf(type: String): ExtensionReceiver {
            val (base, nullable) = type.withoutNullability()
            val name = base.substringBefore('<')
            if (base.isFunctionType() || !name.matches(className)) return ExtensionReceiver(base, nullable = nullable)
            val arguments = typeArgumentsOf(base)
            return ExtensionReceiver(name, arguments, nullable, variances = arguments.map { null })
        }

        /** The rendering of this type argument, like `out Number` or `*`, `null` if it has a type parameter. */
        private fun KTypeProjection.render(): String? = if (type?.hasTypeParameter() == true) null else toString().unqualified()

        private fun KType.hasTypeParameter(): Boolean =
            classifier is KTypeParameter || arguments.any { it.type?.hasTypeParameter() == true }

        /** The indices of the type arguments of this type of a type parameter not matching any type argument, see [ExtensionReceiver.constrainedArguments]. */
        private fun KType.constrainedArguments(): Set<Int> {
            val uses = HashMap<KTypeParameter, Int>()
            fun KType.count() {
                (classifier as? KTypeParameter)?.let { uses.merge(it, 1, Int::plus) }
                arguments.forEach { it.type?.count() }
            }
            count()
            return arguments.indices.filterTo(HashSet()) { index ->
                val argument = arguments[index].type
                val parameter = argument?.classifier as? KTypeParameter
                argument != null && argument.hasTypeParameter() && (
                    parameter == null
                        || argument.isMarkedNullable
                        || uses[parameter] != 1
                        || parameter.upperBounds.singleOrNull().let { it == null || it.classifier != Any::class || !it.isMarkedNullable }
                    )
            }
        }

        /**
         * This rendered type without its nullability, and whether it is nullable,
         * like `String` of `String?`, or `(Int) -> Int` of `((Int) -> Int)?`, but not of `(Int) -> Int?`, a function type of a nullable result,
         * or of a platform type, like `String!`, not nullable.
         */
        private fun String.withoutNullability(): Pair<String, Boolean> {
            val unwrapped = removeSuffix("?").let { if (it.isParenthesized()) it.substring(1, it.length - 1) else null }
            val nullable = endsWith("?") && (unwrapped != null || !isFunctionType())
            return (unwrapped ?: if (nullable) removeSuffix("?") else removeSuffix("!")) to nullable
        }

        private fun String.unqualified(): String = expandAliases().replace(qualifier, "")

        /**
         * This rendered type with each type alias replaced by its expansion, the innermost first,
         * like `java.lang.StringBuilder` of `kotlin.text.StringBuilder /* = java.lang.StringBuilder */`,
         * as a compiler message renders the type of an expression by its expansion,
         * left as is if an alias cannot be told for sure, like one of a function type argument.
         */
        private fun String.expandAliases(): String {
            var rendered = this
            while (true) {
                val start = rendered.lastIndexOf(ALIAS_EXPANSION_START)
                if (start < 0) return rendered
                val end = rendered.indexOf(ALIAS_EXPANSION_END, start + ALIAS_EXPANSION_START.length)
                if (end < 0) return this
                var aliasStart = start
                if (rendered.getOrNull(aliasStart - 1) == '>') {
                    var depth = 0
                    do {
                        when (rendered[--aliasStart]) {
                            '>' -> depth++
                            '<' -> depth--
                            '-', '(', ')' -> return this
                        }
                    } while (depth > 0 && aliasStart > 0)
                    if (depth != 0) return this
                }
                while (aliasStart > 0 && rendered[aliasStart - 1].let { it.isLetterOrDigit() || it == '_' || it == '.' }) aliasStart--
                if (aliasStart == start) return this
                rendered = rendered.substring(0, aliasStart) +
                    rendered.substring(start + ALIAS_EXPANSION_START.length, end) +
                    rendered.substring(end + ALIAS_EXPANSION_END.length)
            }
        }

        /** Whether this rendered type is wrapped in a pair of parentheses, like `((Int) -> Int)`, but not `(Int) -> (Int)`. */
        private fun String.isParenthesized(): Boolean {
            if (!startsWith("(") || !endsWith(")")) return false
            var depth = 0
            forEachIndexed { index, char ->
                when (char) {
                    '(' -> depth++
                    ')' -> if (--depth == 0) return index == length - 1
                }
            }
            return false
        }

        /**
         * Whether this rendered type is a function type, like `(Int) -> String?` or `List<Int>.() -> Int`,
         * an arrow of its own, not of a type argument.
         */
        private fun String.isFunctionType(): Boolean {
            var depth = 0
            forEachIndexed { index, char ->
                when (char) {
                    '<', '(' -> depth++
                    ')' -> depth--
                    '>' -> if (index > 0 && this[index - 1] == '-') { if (depth == 0) return true } else depth--
                }
            }
            return false
        }

        /** The type arguments of this rendered [type], like `Int` and `(Int, Int) -> Int` of `Map<Int, (Int, Int) -> Int>`. */
        private fun typeArgumentsOf(type: String): List<String> {
            if (!type.endsWith(">")) return emptyList()
            return type.substring(type.indexOf('<') + 1, type.length - 1).splitAtTopLevel(",").map { it.trim() }
        }

        /** This rendered type split at each [delimiter] outside of type arguments and parentheses, like `A & B<C & D>` into `A` and `B<C & D>`. */
        private fun String.splitAtTopLevel(delimiter: String): List<String> {
            val result = mutableListOf<String>()
            var depth = 0
            var start = 0
            var index = 0
            while (index < length) {
                when (this[index]) {
                    '<', '(' -> depth++
                    ')' -> depth--
                    '>' -> if (index == 0 || this[index - 1] != '-') depth--
                }
                if (depth == 0 && startsWith(delimiter, index)) {
                    result += substring(start, index)
                    index += delimiter.length
                    start = index
                } else {
                    index++
                }
            }
            result += substring(start)
            return result
        }

    }

}
