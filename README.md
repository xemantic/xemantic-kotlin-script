# xemantic-kotlin-script

Kotlin script execution with LLM-friendly diagnostics

[<img alt="Maven Central Version" src="https://img.shields.io/maven-central/v/com.xemantic.kotlin/xemantic-kotlin-script">](https://central.sonatype.com/artifact/com.xemantic.kotlin/xemantic-kotlin-script)
[<img alt="GitHub Release Date" src="https://img.shields.io/github/release-date/xemantic/xemantic-kotlin-script">](https://github.com/xemantic/xemantic-kotlin-script/releases)
[<img alt="license" src="https://img.shields.io/github/license/xemantic/xemantic-kotlin-script?color=blue">](https://github.com/xemantic/xemantic-kotlin-script/blob/main/LICENSE)

[<img alt="GitHub Actions Workflow Status" src="https://img.shields.io/github/actions/workflow/status/xemantic/xemantic-kotlin-script/build-main.yml">](https://github.com/xemantic/xemantic-kotlin-script/actions/workflows/build-main.yml)
[<img alt="GitHub branch check runs" src="https://img.shields.io/github/check-runs/xemantic/xemantic-kotlin-script/main">](https://github.com/xemantic/xemantic-kotlin-script/actions/workflows/build-main.yml)
[<img alt="GitHub commits since latest release" src="https://img.shields.io/github/commits-since/xemantic/xemantic-kotlin-script/latest">](https://github.com/xemantic/xemantic-kotlin-script/commits/main/)
[<img alt="GitHub last commit" src="https://img.shields.io/github/last-commit/xemantic/xemantic-kotlin-script">](https://github.com/xemantic/xemantic-kotlin-script/commits/main/)

[<img alt="GitHub contributors" src="https://img.shields.io/github/contributors/xemantic/xemantic-kotlin-script">](https://github.com/xemantic/xemantic-kotlin-script/graphs/contributors)
[<img alt="GitHub commit activity" src="https://img.shields.io/github/commit-activity/t/xemantic/xemantic-kotlin-script">](https://github.com/xemantic/xemantic-kotlin-script/commits/main/)
[<img alt="GitHub code size in bytes" src="https://img.shields.io/github/languages/code-size/xemantic/xemantic-kotlin-script">]()
[<img alt="GitHub Created At" src="https://img.shields.io/github/created-at/xemantic/xemantic-kotlin-script">](https://github.com/xemantic/xemantic-kotlin-script/commits)
[<img alt="kotlin version" src="https://img.shields.io/badge/dynamic/toml?url=https%3A%2F%2Fraw.githubusercontent.com%2Fxemantic%2Fxemantic-kotlin-script%2Fmain%2Fgradle%2Flibs.versions.toml&query=versions.kotlin&label=kotlin">](https://kotlinlang.org/docs/releases.html)
[<img alt="discord users online" src="https://img.shields.io/discord/811561179280965673">](https://discord.gg/vQktqqN2Vn)
[![Bluesky](https://img.shields.io/badge/Bluesky-0285FF?logo=bluesky&logoColor=fff)](https://bsky.app/profile/xemantic.com)

## Why?

LLM agents can act by writing code, and Kotlin scripts are a good medium for it:
statically typed, concise, and able to call any JVM API.
But the agent needs to understand what went wrong when its script fails,
and the raw output of the Kotlin scripting host is not made for that.

This library executes Kotlin scripts and reports compilation and runtime errors in a form an LLM can act on:
each error points to the offending line of the original script,
with the problematic fragment marked with `<error>` tags.

Extracted from [Golem XIV](https://github.com/xemantic/golem-xiv).

## Usage

In `build.gradle.kts` add:

```kotlin
dependencies {
    implementation("com.xemantic.kotlin:xemantic-kotlin-script:0.1.0")
}
```

Then:

```kotlin
val executor = ScriptExecutor()
val result = executor.execute("""
    import kotlinx.coroutines.delay
    
    delay(100)
    "${'$'}greeting ${'$'}{names.joinToString()}".uppercase()
""".trimIndent()) {
    "greeting" provides "Hello"
    "names" provides listOf("Alice", "Bob")
}
when (result) {
    is ScriptResult.Value -> println(result.value) // HELLO ALICE, BOB
    is ScriptResult.Error -> println(result.message)
}
```

* The script body runs as a suspend block with a `CoroutineScope` receiver, in the coroutine of the caller:
  the caller awaits all the coroutines the script launches, and cancelling the caller cancels the script.
  Cancellation cannot interrupt a blocking call of the script though, like `Thread.sleep`.
  Unlike in a regular `.kts` file, `this` in the script body is therefore this `CoroutineScope`, not the script instance.
  In a function of the script body it is reported as an error,
  so is a call there of a member of `Any`, or of its scope function, without a receiver, like `toString()` or `apply { }`,
  as in a function of a regular script they refer to the script instance,
  while the API of the `CoroutineScope`, like `launch { }` or `isActive`, can be used in the functions of the script body as well,
  and the `CoroutineScope` itself can be referred to as `this@_script` anywhere in the script body.
* The script is compiled and evaluated on `Dispatchers.IO`, as both might block.
  Another dispatcher can be given as `ScriptExecutor(dispatcher = ...)`,
  e.g. `Dispatchers.IO.limitedParallelism(4)` to bound the threads used by scripts,
  or `StandardTestDispatcher(testScheduler)` to run scripts in the virtual time of `runTest`.
* Dependencies are exposed to the script as properties of the given name and type.
  The type is inferred from the value, including its type arguments,
  or can be given explicitly, e.g. `provide<String?>("name", null)` or `provide<Clock>("clock", fixedClock)`,
  to make the dependency nullable, or to expose it only as its supertype.
  A dependency list built dynamically can be passed instead, as `execute(script, listOf(dependency("greeting", "Hello")))`.
  Invalid dependencies are rejected with `IllegalArgumentException`:
  a name which is not a Kotlin identifier, a duplicate name, a value which is not of the type,
  a name which the script body would resolve on its `CoroutineScope` receiver instead, like `coroutineContext` or `javaClass`,
  or such a function name, like `launch` or `let`, for a dependency which can be called,
  or a type which the script cannot refer to, like a private or a local class.
  Where this document compares the script with a regular `.kts` file,
  the dependencies are the members of an abstract base class of that script (`baseClass` of the scripting host), not provided properties (`providedProperties`),
  so a function of the script shadows a callable dependency of its name:
  `x()` calls `fun x(): Int = 10` of the script, also when the call precedes the function, while a regular script would prefer a provided property `x`
  ([#101](https://github.com/xemantic/xemantic-kotlin-script/issues/101)).
* A reference to a dependency preceding the declaration of a local property of the same name, like `x` in `fun show() = x` followed by `val x = 2`,
  or a call preceding the declaration of a local function of the same name,
  or of a local class, like `Foo(2)` followed by `class Foo(val v: Int)`, which constructs it, if a constructor applies
  (unlike an object with the `invoke` operator, a `fun interface`, or a class with a companion `invoke`, which are not checked),
  is reported as an error, as in a regular script it refers to the declaration.
  Such a call within the lambda of a scope function receiving the script instance, which calls the dependency in a regular script,
  is recognized only for an unqualified `run`, `apply`, `runCatching` or `with`, while e.g. `kotlin.with(this) { Foo(2) }`,
  or a scope function declared by the script, is reported as well.
* A call preceding the declaration of a local function of its name, like `print("x")` followed by `fun print(x: Any)`,
  which calls another function instead, like `print` of the standard library, or fails, like an operator resolved to the `CoroutineScope`,
  is reported as an error, if the local function applies, as in a regular script it calls the local function,
  unless the call is resolved to a member of a receiver, like `append` in `buildString { append("x") }`, as in a regular script.
* A function accessing the local declarations of the script body stays local to the script block, where it is visible only after its declaration,
  so a call preceding it, like `b(x)` in `fun a(x: Int) = b(x) + 1` followed by `fun b(x: Int) = x * factor`, is unresolved there.
  Then the script is compiled again with such functions declared preceding the statements calling them, each preceding the ones calling it,
  if they are the only unresolved references, and the script compiles then
  ([#4](https://github.com/xemantic/xemantic-kotlin-script/issues/4)).
  The call stays reported as unresolved, with the hint to declare the function earlier, when the hoisting might change how any other name is resolved:
  when the function refers to a declaration declared between the statement calling it and itself, like another function it calls,
  or to a local `var`, or when it is an operator, or an operator is declared in between,
  or when its type is inferred, while it is an extension, or contains a lambda, an anonymous function, or a class,
  as a regular script calling it before its declaration resolves all of its functions after the members of the implicit receivers then, like `b()` in `with(obj) { b() }`,
  or when another declaration of the script has its name,
  or when a statement in between refers to its name otherwise, like a member of a receiver, `with(obj) { b() }`, or a parameter,
  or when a call in between, or within it, is checked as resolved to another declaration than in a regular script, like `print` declared by the script later.
  When the function accesses a local property declared after the call, the hint tells to declare them both earlier, or to pass the property as a parameter.
  Functions calling each other, like `even` and `odd`, cannot be declared one preceding the other,
  which the hint tells, suggesting to pass the local declarations they access as parameters instead, so they are compiled outside of the script body.
* Scripts executed repeatedly, like an agent harness defined as a script, can skip the recompilation
  with `ScriptExecutor(compilationCacheSize = ...)`, keeping that many compiled scripts, the least recently used evicted first.
  A compiled script is reused when the script text, and the names and types of the dependencies are the same,
  while the dependency values might differ.
  A script which failed to compile is compiled again on the next execution,
  unlike a compiled one rejected by the checks following the compilation,
  like the reference to a dependency preceding the declaration of the same name,
  which is served from the cache, reporting the same error,
  unless the compilation itself also reported an error.
* The invocations of the compiler running at the same time are bounded by `ScriptExecutor(maxConcurrentCompilations = ...)`,
  the number of the available processors by default,
  while the other compilations of the same executor wait for their turn.
  Each invocation loads the whole classpath, so lower it when the heap is small,
  otherwise the concurrent compilations might fail with `OutOfMemoryError`, which is thrown, not reported as an error of the script.
* The value of the last expression of the script becomes `ScriptResult.Value.value`, or `Unit` if there is none.
* The script is compiled against the whole classpath of the current context,
  so any class available to the host application can be imported by the script.
* Declarations which cannot be local to a block, like enum classes, objects, interfaces or sealed types,
  are compiled outside of the script body, together with the types and functions they refer to.
  So is a property with a getter or a setter having a body, like `val total get() = items.size`, as a local property cannot have one.
  Therefore, they cannot refer to the local values of the script body, only to the dependencies,
  except the ones initialized with a literal, like `val taxRate = 0.2`, which are moved together with them,
  and referring to a local value or function of the same name as a dependency is reported as unresolved, instead of using the dependency,
  while an object, an enum class, an interface or a nested class cannot refer even to the dependencies,
  as they are the members of the script instance.
  An error message will hint at this, if it happens,
  also when such a reference compiles, as it is resolved to another declaration of the same name, like `check` of the standard library.

### Known limitations

The script body is compiled as a block, which differs from a regular script in how the names are resolved,
when the declarations of the script have the same names as each other, or as the dependencies.
Most of these cases are reported as errors, while the following ones still resolve differently, without an error:

* a property of the script body is resolved before a member of the same name of an implicit receiver,
  like `name` in `user.run { name }` with `val name = "script"`, as it is a local variable of the block ([#36](https://github.com/xemantic/xemantic-kotlin-script/issues/36)),
* a call of a function, of which only some overloads are compiled outside of the script body, when it precedes a local overload,
  or has a receiver, like `"a".fmt(1)` of the extension functions, or when a local overload refers to a local class, while the overloads are of both inferred and declared return types ([#30](https://github.com/xemantic/xemantic-kotlin-script/issues/30)),
* a call of local overloads, like `show(1)` with `fun show(x: Int): String = "int"` and `fun show(x: Any) = "any"`, which calls the most specific one, `show(x: Int)`,
  while a regular script prefers the one with the return type inferred from its expression body within a top-level statement, or a declaration of an inferred type, like `fun h() = show(1)`,
  which seems to be a quirk of the compiler rather than a rule of the language ([#100](https://github.com/xemantic/xemantic-kotlin-script/issues/100)),
* a call of a function, of which only some overloads are compiled outside of the script body, within a default value of a parameter of a function of an inferred return type,
  like `show(1)` in `fun h(x: String = show(1)) = x`, which calls the local overload of an inferred type, while a regular script calls the most specific one ([#102](https://github.com/xemantic/xemantic-kotlin-script/issues/102)),
* a call from the script body to a local function referring to a type alias declared after it, which is not visible to the function in a regular script, so the function does not apply there,
  like `check(true)` calling `fun check(n: N)` followed by `typealias N = Boolean`, instead of `check` of the standard library, unless the call is within a declaration of an inferred type, and the function overloads one compiled outside of the script body ([#102](https://github.com/xemantic/xemantic-kotlin-script/issues/102)),
* a call preceding a local class named like a callable dependency, when only a constructor with a parameter of a local class declared after the call applies, like `Foo(null)` followed by `class Bar` and `class Foo(val b: Bar?)` ([#31](https://github.com/xemantic/xemantic-kotlin-script/issues/31)),
* a call from outside of the script body to a local property, whose function type is known only to the compiler, like `val print = printer()`,
  when the compiler names its type differently than it is visible there, like a local class, or a class which is not imported,
  or does not name it as a valid type, like an anonymous object of a function type, `val print = object : (Any?) -> Unit { ... }`, or a platform type, like `String!`,
  or when it is made callable by an `invoke` operator extension declared by the script, like `operator fun Int.invoke(x: Any?)` of `val print = 1 + 2`,
  or when the property is assigned after its declaration, like `val print: Printer` followed by `print = Printer()`,
  or when another statement follows the property on the same line ([#32](https://github.com/xemantic/xemantic-kotlin-script/issues/32)),
* a call preceding a local function named like a callable dependency, which refers to a local class, when the call is within the declaration of this class, or follows it on the same line,
  passing a value of this class, like `f(P())`, or when the call follows it, which another call of its name precedes, while the function refers also to a local class declared after the call, and the call applies to the function regardless of the former,
  like `f(null, null)` following `f(1, 1)` and `class P`, followed by `class R` and `fun f(p: P?, r: R?)` ([#33](https://github.com/xemantic/xemantic-kotlin-script/issues/33)),
* a call preceding a local function, which compiles, when another local function of its name is visible already,
  or when it is within a lambda, a class, or an extension function, while the local function refers to a local class, or is named like a function of its `CoroutineScope`, like `run { V(1) + V(2) }` calling another `plus` before `operator fun V.plus(o: V)` ([#51](https://github.com/xemantic/xemantic-kotlin-script/issues/51)),
* a call within a lambda to a property compiled outside of the script body, named like a function of its `CoroutineScope`, whose function type is known only to the compiler, like `val launch get() = launcher()` ([#44](https://github.com/xemantic/xemantic-kotlin-script/issues/44)).

Giving the declarations and the dependencies distinct names avoids them.

### Error reporting

A script like:

```kotlin
asdf
println(foo)
```

produces a `ScriptResult.Error` with the `ExecutionPhase.COMPILATION` phase, and the message:

```
script.kts:1:1: error: Unresolved reference 'asdf'.
  | <error>asdf</error>

script.kts:2:9: error: Unresolved reference 'foo'.
  | println(<error>foo</error>)
```

Each diagnostic follows the `file:line:column: severity: message` format of kotlinc and other compilers,
which LLMs are familiar with.
The offending fragment of the source line is marked with `<error>` tags instead of a caret line below,
so that the LLM does not have to count columns.

Runtime exceptions are reported in the `ExecutionPhase.EVALUATION` phase,
with the stack frames of the script only, together with the source line of each frame,
followed by compiler warnings, if any, as they might explain the failure:

```
java.lang.Exception: must fail
  at <script body>(script.kts:1)
  | throw Exception("must fail")
```

The frames are named after the functions of the script, e.g. `at Checker.check(script.kts:5)`,
and repeated frames of a recursion are collapsed.

Compiler warnings of a successful script are available as `ScriptResult.Value.warnings`.
They might reveal a bug in a script which nevertheless succeeded,
so it is up to the caller whether to show them to the LLM.
It is up to the caller how to present the error to the LLM, e.g.:

```kotlin
"<impediment phase=\"${result.phase}\">\n${result.message}\n</impediment>"
```

## Security

The script runs with the full privileges of the host application, there is no sandbox.
It is compiled against the whole classpath, and executed in the JVM of the host,
so it can do whatever the host can:
read and write files, open network connections, read the environment variables and system properties,
access the classes and singletons of the host through reflection,
start processes, or terminate the JVM with `System.exit`.
The dependencies limit what the script is given, not what it can reach.

A script written by an LLM should be treated as untrusted code,
as it might be the result of a prompt injection, or just a hallucination.
If the LLM can be influenced by content which is not trusted,
like web pages, documents, emails or tool results,
execute the scripts in an isolated environment,
e.g. a separate JVM process running in a container or a virtual machine,
with no secrets, limited file system and network access, and bounded CPU, memory and execution time.

## Logging

The library logs through [kotlin-logging](https://github.com/oshai/kotlin-logging) over SLF4J.
Add an SLF4J provider, e.g. logback, to see the `DEBUG` logs of the compilation and evaluation phases.

## License

[GNU Affero General Public License v3.0](LICENSE)

## Development

See [DEVELOPMENT.md](DEVELOPMENT.md) for the documentation conventions,
and for maintenance notes on updating the gradle wrapper and the project dependencies.
