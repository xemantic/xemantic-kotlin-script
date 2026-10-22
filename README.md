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
* A reference to a dependency preceding the declaration of a local property of the same name, like `x` in `fun show() = x` followed by `val x = 2`,
  or a call preceding the declaration of a local function of the same name,
  is reported as an error, as in a regular script it refers to the declaration.
* Scripts executed repeatedly, like an agent harness defined as a script, can skip the recompilation
  with `ScriptExecutor(compilationCacheSize = ...)`, keeping that many compiled scripts, the least recently used evicted first.
  A compiled script is reused when the script text, and the names and types of the dependencies are the same,
  while the dependency values might differ.
* The value of the last expression of the script becomes `ScriptResult.Value.value`, or `Unit` if there is none.
* The script is compiled against the whole classpath of the current context,
  so any class available to the host application can be imported by the script.
* Declarations which cannot be local to a block, like enum classes, objects, interfaces or sealed types,
  are compiled outside of the script body, together with the types and functions they refer to.
  So is a property with a getter or a setter having a body, like `val total get() = 42`, as a local property cannot have one,
  unless it reads the local values of the script body, like `val total get() = items.size`, outside of a lambda,
  which turns it into a local property delegated to an object having its accessors,
  as long as they do not use the backing `field`, nor `this`, nor the property itself, nor have the annotations or the modifiers, like `private set`,
  nor refer without a receiver to a name, which is not declared by the script, the dependencies or the explicit (not star) imports,
  nor to a name, which might be resolved on the receivers of the script body instead, like `let { }` or `isActive`,
  unless it is a local value declared before, nor to a property declared after,
  and it is not an extension property, nor referred to by a callable reference.
  It is still compiled outside of the script body, when it reads the local values only through the local functions,
  or only the values initialized with a literal, or a local value named `field`,
  or within a lambda with a receiver, like `buildString { append(prefix) }` or `with(items) { size }`.
  So is an inline function, as a local function cannot be inline,
  unless it reads the local values of the script body outside of a lambda, directly or through the local functions,
  like `inline fun logged(block: () -> Unit) { log += "start"; block() }`,
  which compiles it as a local function without the `inline`, `noinline` and `crossinline` modifiers,
  as long as it is not an extension function, nor overloaded, nor recursive, has no reified type parameters, no context parameters, no contract,
  no annotations but `@Suppress`, no modifiers but the visibility and `suspend`, does not refer to `this` or `super`,
  is not called before its declaration,
  does not refer within a lambda to a name declared by the script body staying there, which might be resolved on its receiver instead, like `name` in `user.run { name }`,
  so also not when reading a local value only within a lambda, like `log` in `items.forEach { log += it }`,
  nor to a name, which might be resolved on the receivers of the script body instead, like `isActive`, unless it is a local value declared before,
  nor to a property or a function declared after, which stays in the script body, unlike a function compiled outside of it, like `helper` in `fun helper() = 1`,
  and the lambdas passed to it do not return, break or continue to outside of them, like `return` in `fun first(): Int { logged { return 1 }; return 0 }`.
  A call of such a function is resolved after the members of the receivers, like in a regular script, also within a lambda, a class, or an extension function,
  like `logged` in `listOf(1, 2).forEach { logged { } }`, unless it is named like a function of the coroutine scope of the script body, like `run`,
  or like a dependency, or referred to by a callable reference, like `::logged`,
  in which case it is compiled outside of the script body, if called where a receiver might resolve the call instead, like `run` in `class A { fun go() = run { } }`.
  Unlike when inlined, these lambdas cannot call the suspending functions, which is reported as an error with a hint,
  and the warning of an inline function without parameters of function types is not reported.
  Therefore, they cannot refer to the local values of the script body, only to the dependencies,
  except the ones initialized with a literal, like `val taxRate = 0.2`, which are moved together with them,
  unless their type names a local class staying in the script body, like `val x: Foo? = null`,
  so a function reading only such values, like `fun tax(x: Double) = x * taxRate`, is moved together with them as well,
  when they call it, or when it is called before its declaration,
  unless called before the declaration of such a value, like `f` in `val r = f()` followed by `val k = 5` and `fun f() = k`,
  which a regular script reads before it is initialized, as `0` or `null`,
  reported also when such a call is only stored, like `{ f() }` or `::f`, or never made, like `if (false) f()`,
  or when its return type is inferred, and it is called before its declaration only where a receiver might resolve the call instead, like `append` in `buildString { append("x") }`,
  and also after it, where a receiver might resolve the call, like in a following `buildString { append("y") }`, within a lambda, an extension function,
  or a class, which might have a member of the same name,
  or when it is called before its declaration within a class having a member of the same name, like `g` in `class A { fun f() = g(); fun g() = 1 }`,
  as no placement outside of the script body resolves all these calls like a regular script (see the known limitations below),
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
* overloads of a function, of which only some are compiled outside of the script body ([#30](https://github.com/xemantic/xemantic-kotlin-script/issues/30)),
* a call preceding a local class named like a callable dependency ([#31](https://github.com/xemantic/xemantic-kotlin-script/issues/31)),
* a call from outside of the script body to a local property, whose function type is known only to the compiler, like `val print = printer()` ([#32](https://github.com/xemantic/xemantic-kotlin-script/issues/32)),
* a call preceding a local function named like a callable dependency, which refers to a local class declared later ([#33](https://github.com/xemantic/xemantic-kotlin-script/issues/33)),
* a call within a lambda to a function compiled outside of the script body, named like a function of its `CoroutineScope`, like `cancel` ([#44](https://github.com/xemantic/xemantic-kotlin-script/issues/44)),
* a call where a receiver might resolve it, like within a lambda, following the declaration of a function, which is called before its declaration only where the receivers resolve the calls instead,
  like the second `append` in `buildString { append("x") }`, `fun append(t: String) = t + "!"`, `fun g() = buildString { append("y") }`,
  as the function is resolved after the receivers, like when called before its declaration, while a regular script resolves it before them,
  when its return type is inferred, unlike a declared one, like `fun append(t: String): String`
  ([#73](https://github.com/xemantic/xemantic-kotlin-script/issues/73)), unless the function reads the values initialized with a literal, which keeps it in the script body,
  where a call within a declaration compiled outside of the script body is resolved to a member of the same name of its class, while a regular script resolves it to the function,
* a call within a class to its own member, preceding the declaration of a function of the same name with an inferred return type, which is compiled outside of the script body,
  like `g` in `class A { fun f() = g(); fun g() = 1 }` followed by `fun g() = 10`, as the call is resolved to the function, while a regular script resolves it to the member
  ([#75](https://github.com/xemantic/xemantic-kotlin-script/issues/75)), unless the function reads the values initialized with a literal, which keeps it in the script body.

Giving the declarations and the dependencies distinct names avoids them.

A function called by the script body before its declaration is compiled after it,
so the raw JVM line numbers of its frames, seen only by a script inspecting its own stack trace, differ from the ones of the script,
while the messages of the errors refer to the lines of the script.

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
