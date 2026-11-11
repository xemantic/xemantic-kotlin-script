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

This library executes Kotlin snippets in stateful sessions, like a Kotlin notebook,
so an agent can build on what it declared before,
and reports compilation and runtime errors in a form an LLM can act on:
each error points to the offending line of the original snippet,
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
ScriptSession {
    "greeting" provides "Hello"
}.use { session ->
    session.execute("""
        val names = mutableListOf("Alice")
        fun greet() = "${'$'}greeting ${'$'}{names.joinToString()}"
    """.trimIndent())
    session.execute("names += \"Bob\"")
    when (val result = session.execute("greet().uppercase()")) {
        is ScriptResult.Value -> println(result.value) // HELLO ALICE, BOB
        is ScriptResult.Error -> println(result.message)
    }
}
```

* A `ScriptSession` is stateful, like a Kotlin notebook:
  the declarations of an executed snippet, its `val`s, `var`s, functions, classes, objects and imports,
  stay visible to the snippets executed after it, and can be redeclared by them.
  A redeclaration is seen by the snippets following it,
  while the functions of the earlier snippets keep calling the declarations they were compiled against,
  so after redeclaring `greet`, a `shout` calling it has to be redeclared as well to call the new one.
  A snippet which fails to compile leaves no declarations behind,
  while a snippet throwing an exception keeps the ones it declared,
  also the properties it did not initialize before throwing,
  which hold `null` then, despite their type, or zero, or `false`, if it is a primitive one, like `Int`, or `Boolean`.
  Redeclaring such a property is not enough, if a function, or a class, compiled against it reads it,
  like a `fun greet() = name.uppercase()` declared together with `val name: String = error("boom")`,
  as it keeps reading the uninitialized one — it has to be redeclared as well, with the declarations calling it,
  while assigning a `var` is enough, as all of them read the same one.
  The error message of the failing snippet names the properties it left `null` despite their type,
  the first five `val`s and the first five `var`s, counting the rest,
  with a hint to declare the `val`s again, together with the declarations reading them, and to assign the `var`s.
  A `NullPointerException` of the JVM naming such a property later, like of `s.length` after `val s: String = error("boom")`,
  gets the hint as well, naming the declarations reading it, and the ones calling them, found in the stack trace, like `greet`.
  Neither hint covers a property of a primitive type, which reads as zero, or `false`, without failing,
  nor a property of a nullable type, or of a platform type, like of `System.getProperty`, which is `null` by its type.
  The later hint also needs the JVM to name the property in the message of the `NullPointerException`.
  The JVM does not name it for a value copied from the property before the failing read, like `val t = s` followed by `t.length`,
  nor for the property passed as an argument, rejected by the check of a non-null parameter (`Parameter specified as non-null is null`),
  nor when it runs without the helpful `NullPointerException` messages (`-XX:-ShowCodeDetailsInExceptionMessages`).
  A delegated property is the exception, as it is found by its getter in the stack trace instead.
* Each snippet is a regular Kotlin script, compiled by the K2 REPL compiler of the Kotlin scripting API,
  against the whole classpath of the host application,
  so any class available to the host can be imported by a snippet.
* The value of the last expression of a snippet becomes `ScriptResult.Value.value`, or `Unit` if there is none.
* All the snippets of a session are compiled and evaluated on its own thread, one after another,
  so a snippet can use an API bound to the thread which created it.
  Snippets executed concurrently wait for their turn.
  The thread is given a 16 MB stack, larger than the default one,
  so the compiler takes a deeply nested expression, of thousands of nesting levels,
  and a snippet can recurse deeper.
  The caller of `execute` resumes where its dispatcher resumes it, like the main thread for `Dispatchers.Main.immediate`,
  or from `Dispatchers.Default` if it would resume in place, on the thread of the session:
  if it has no dispatcher, like `suspend fun main`, or one which does not dispatch, like `Dispatchers.Unconfined`,
  it runs on `Dispatchers.Default` then,
  and if it has a continuation interceptor which is not a dispatcher, the interceptor takes it over there,
  like one posting it to its own thread.
  Such a caller needs a free worker of `Dispatchers.Default` to resume,
  so a snippet blocking all of them, like `repeat(n) { launch { latch.await() } }`, keeps it waiting,
  and it should not block its thread for long,
  while the next snippets do not wait for it, as the turn of the caller ends with its snippet,
  so the snippet freeing the workers can still run.
  Only a dispatcher running its tasks on the dispatching thread, like `Executor(Runnable::run).asCoroutineDispatcher()`,
  resumes its caller on the thread of the session.
  A dispatcher failing to tell whether it dispatches, or to dispatch, on the thread of the session, is retried from `Dispatchers.Default`,
  and one failing there as well, like one always throwing, or the one of an executor shut down,
  is bypassed: the caller resumes without it, on `Dispatchers.Default`, failing with its exception,
  so it never stays suspended forever.
  Separate sessions run in parallel, each with its own state,
  and each holding its own compiler, which loads the whole classpath, so close the sessions no longer needed.
* A caller waiting for the snippet of another caller, if it would resume in place,
  is resumed on the thread of the session, when the snippet of the other caller ends, and submits its snippet from there,
  so it does not need a free worker of `Dispatchers.Default` before its snippet, which might be the one freeing them.
  If the session is closed meanwhile, such a caller is rejected, with an `IllegalStateException`, from `Dispatchers.Default`,
  not nested in the rejection of the caller it waited for, also when it started on the thread of that caller,
  while a caller of `Dispatchers.Main.immediate` is rejected on its own thread, where its dispatcher resumes it.
* The snippets are received by the `ScriptScope`, a `CoroutineScope` living as long as the session,
  with `kotlinx.coroutines.*` imported by default:
  * `await { ... }` runs suspending code, like the requests of a ktor client,
    blocking the snippet until it completes, on the thread of the session, e.g.
    `val pages = await { urls.map { async { client.get(it).bodyAsText() } }.awaitAll() }`;
  * `launch { ... }` starts a coroutine which keeps running after the snippet,
    on `Dispatchers.Default`, or the `dispatcher` given to `ScriptSession`,
    until it completes, or the session is closed.
* Cancelling the caller of `execute` cancels the coroutines of `await`,
  and interrupts the thread of the session, so a blocking call like `Thread.sleep` fails,
  while a snippet which neither blocks interruptibly, nor awaits, runs to its end.
  A snippet being compiled when the caller is cancelled is compiled and evaluated nevertheless,
  as the compiler cannot be interrupted, and would evaluate it before the next snippet anyway.
* Closing the session cancels the coroutines launched by its snippets,
  and the snippet being evaluated, like cancelling its caller does, also a caller which cannot be cancelled, like `suspend fun main`,
  so its `execute` returns an evaluation error,
  unless the snippet runs to its end, as above, or catches the `InterruptedException`, or the `CancellationException` of `await`,
  and ends without blocking, or awaiting, again, as its evaluation stays cancelled.
  Then it releases its compiler, thread, and the values of its properties, once that snippet ends,
  while the snippets and coroutines still running keep reading the properties from the fields of their scripts, unaffected by the release.
* Properties of the given name and type are provided to the snippets of the session, like `"greeting" provides "Hello"`.
  The type is inferred from the value, including its type arguments,
  or can be given explicitly, e.g. `provide<String?>("name", null)` or `provide<Clock>("clock", fixedClock)`,
  to make the property nullable, or to expose it only as its supertype.
  A mutable collection keeps its mutable type,
  so `"names" provides mutableListOf("Alice")` lets the snippets modify the list shared with the host,
  while `provide<List<String>>("names", names)` makes the snippets see it as read-only.
  A read-only collection of the Kotlin standard library, like `emptyList()`, is rejected as a value of a mutable type,
  while a frozen collection implementing the mutable interface, like the result of `buildList`, or a Java collection, like `Collections.unmodifiableList`,
  is not detected, and fails when mutated.
  Only the class of the value is checked against the type, as the type arguments are erased at runtime,
  so a nested collection of another mutability than declared, like an `emptySet()` value in a `MutableMap<String, MutableSet<Int>>`,
  is not detected either, and fails when mutated.
  Only `typeOf` and the types of the members keep the mutability,
  while `MutableList::class.createType(...)` yields a read-only `List` type, as `MutableList::class` is the same class as `List::class`.
  A property list built dynamically can be passed instead, as `ScriptSession(listOf(property("greeting", "Hello")))`.
  Invalid properties are rejected with `IllegalArgumentException`:
  a name which is not a Kotlin identifier, a duplicate name, a value which is not of the type,
  a name of a member of the `ScriptScope`, like `await` or `coroutineContext`,
  or a type which the snippets cannot refer to, like a private or a local class,
  or a `KType` implemented by hand whose classifier is not a class, or whose type arguments do not match the type parameters of its class.
  A property which passes this validation might still fail to be declared,
  like one of a type the compiler rejects, e.g. `Enum<String>` created with `createType`,
  or one of a class loaded by another class loader than the one of the session, which its value cannot be cast to.
  Such a failure is reported by every `execute`, as a `ScriptResult.Error` starting with `The properties cannot be declared:`,
  in the `ExecutionPhase` in which the declaration failed, without executing the snippet.
* A snippet can add Maven dependencies to the session, like a `.main.kts` script:
  ```kotlin
  @file:DependsOn("org.jsoup:jsoup:1.16.1")
  import org.jsoup.Jsoup
  Jsoup.parse("<p>Hello</p>").text()
  ```
  The dependencies, with their transitive ones, are resolved from Maven Central,
  or the repositories declared with `@file:Repository("https://...")` instead, by this or any earlier snippet,
  into the local Maven repository, `~/.m2/repository` by default,
  and stay available to the snippets executed after it.
  A class available to the host takes precedence over the one of the same name in a dependency,
  so a dependency cannot replace a library of the host with another version of it.
  A dependency which cannot be resolved fails the snippet with an `ExecutionPhase.COMPILATION` error pointing to its `@file:DependsOn` line.
* A dependency can be declared without version, like `@file:DependsOn("org.jsoup:jsoup")`,
  as an LLM might know only the versions released before its knowledge cutoff.
  It is resolved to its latest version, like `pip install` does:
  the highest stable one in the Maven ordering, among the versions listed by the `maven-metadata.xml` of the repositories,
  neither a `SNAPSHOT`, nor a pre-release, like `2.0-RC1` or `1.9.20-Beta`,
  or the highest pre-release one, if there is no stable one.
  A date-style version, like the `20040616` of `commons-collections:commons-collections`, which Maven orders after any release,
  gives way to any other version of the same stability, like `3.2.2`, if there is one.
  A version with a qualifier, which Maven orders after its release, like `0.8.0-0.6.x-compat` after `0.8.0`,
  gives way to its release, if there is one.
  The LLM should be told the resolved version, as it might not know its API:
  a successful snippet returns it as `ScriptResult.Value.resolvedVersions`, like `mapOf("org.jsoup:jsoup" to "1.24.1")`,
  which, unlike the compiler warnings, is worth showing to the LLM always,
  while a failed snippet reports it in the error message,
  as a warning pointing to the `@file:DependsOn` line, like `Resolved 'org.jsoup:jsoup' to its latest stable version '1.24.1'`.
  The resolved version is kept for the session, so a later snippet declaring the dependency again does not load another version.
  The versions are looked up by Maven Resolver like the dependency is resolved,
  in the repositories declared with their options, like `@file:Repository("https://...", options = ["username=...", "password=..."])`,
  with the mirrors, proxies and server credentials of the Maven settings, `~/.m2/settings.xml` by default,
  among the `maven-metadata.xml` files cached in the local Maven repository, refreshed once a day, or installed into it.
  A repository blocked by a mirror of the settings, like the `maven-default-http-blocker` of Maven blocks the plain `http:` ones,
  is reported as blocked, naming the mirror, when no other repository lists a version.
  The dependencies themselves, also in the versions looked up, are resolved by Kotlin's own resolver,
  which applies the settings with two limitations:
  it resolves a mirrored repository from the mirror directly, bypassing the proxy,
  and it ignores the block of a mirror, so a dependency held only by the blocked repositories
  fails to connect to the URL of the blocking mirror, like `http://0.0.0.0/`, instead of being reported as blocked.
  In the offline mode of the Maven settings, only the `file:` repositories are read,
  along with the `maven-metadata.xml` files already cached from the other repositories,
  and a dependency without any `maven-metadata.xml` there
  is resolved to the latest of its versions present in the local Maven repository.
  A version range, like `[1.0,)`, and the `RELEASE` and `LATEST` versions of Maven are resolved by Maven itself,
  which might select a pre-release version.

### Known limitations

The K2 REPL compiler is still experimental, and these of its bugs are reported to the LLM with a hint how to avoid them:

* A class declared in an earlier snippet is unresolved within a declaration of an inferred type calling it in the same snippet,
  like `fun isPlain(x: Any) = x is Plain` followed by `isPlain(1)`,
  while it resolves with the type declared, `fun isPlain(x: Any): Boolean = x is Plain`.
* A property, or a function, of a value class type, like `Duration` or `UInt`, declared in an earlier snippet,
  fails with `NoSuchMethodError` when called from another snippet,
  as the compiler calls it by its mangled name, like `getTimeout-UwyO8pc`, while it declares it without mangling.
  The provided properties of value class types are not affected, as their getters are declared with the mangled names.
* An `internal` property, or function, or an `internal set` of a property, declared in an earlier snippet,
  also a member of a class declared there,
  fails with `NoSuchMethodError` when called from another snippet,
  as the compiler declares it under a name mangled with the module of its snippet, like `getSecret$snippet1_kts`,
  while it calls it by another name, like `getSecret$main`, or `secret` of a function.
  It works once declared `public`, together with the code calling it, directly or not, declared again, as it keeps calling the `internal` one,
  and with the values depending on it created again: the ones of a class declaring it, or calling it, as they keep being of the old class,
  and the ones holding a lambda calling it, like `val f = { secret() }`.
  The hint names the declarations and the classes on the stack trace as examples only, as it cannot tell all of them.
* A `const val` declared in an earlier snippet cannot be read from another snippet:
  it is unresolved within a declaration of an inferred type, and crashes the compiler wherever else it is read,
  also in a top-level expression, or in a property initializer.
  It works once declared again without `const`.
  A `const val` of an `object` declared in an earlier snippet crashes the compiler as well, without a hint.
* A `@JvmField` property declared in an earlier snippet cannot be accessed from another snippet:
  it is unresolved within a declaration of an inferred type, and fails with `IncompatibleClassChangeError` wherever else it is accessed,
  as the compiler accesses it as an instance field, while it is static.
  It works once declared again without `@JvmField`, together with the code accessing it, directly or not, declared again,
  and with the values depending on it created again, like for an `internal` member.
* An `inline` function, or property, declared in an earlier snippet cannot be called from another snippet,
  and the one referring to the `ScriptScope`, like `coroutineContext`, cannot be compiled at all,
  reported as an internal failure of the compiler.

### Error reporting

A snippet like:

```kotlin
asdf
println(foo)
```

executed as the third snippet of the session,
produces a `ScriptResult.Error` with the `ExecutionPhase.COMPILATION` phase, and the message:

```
snippet3.kts:1:1: error: Unresolved reference 'asdf'.
  | <error>asdf</error>

snippet3.kts:2:9: error: Unresolved reference 'foo'.
  | println(<error>foo</error>)
```

Each diagnostic follows the `file:line:column: severity: message` format of kotlinc and other compilers,
which LLMs are familiar with,
where the snippets are named after their order in the session, `snippet1.kts`, `snippet2.kts`, and so on.
The offending fragment of the source line is marked with `<error>` tags instead of a caret line below,
so that the LLM does not have to count columns.

Runtime exceptions are reported in the `ExecutionPhase.EVALUATION` phase,
with the stack frames of the snippets only, also of the earlier ones declaring the functions called,
together with the source line of each frame,
followed by compiler warnings, if any, as they might explain the failure:

```
java.lang.IllegalArgumentException: value must be positive
  at check(snippet1.kts:2)
  |     require(value > 0) { "value must be positive" }
  at <snippet body>(snippet2.kts:2)
  | values.forEach { check(it) }
```

The frames are named after the functions of the snippets, e.g. `at Checker.check(snippet1.kts:5)`,
and repeated frames of a recursion are collapsed.
The later lines of a multi-line exception message are indented like the frames,
also within a `Caused by:` or a `Suppressed:` exception,
so that they do not break the structure of the report.
The classes declared by the snippets are named as declared, like `Color.BLUE`,
not qualified by the generated snippet class, like `Snippet1.Color.BLUE`.

Compiler warnings of a successful snippet are available as `ScriptResult.Value.warnings`.
They might reveal a bug in a snippet which nevertheless succeeded,
so it is up to the caller whether to show them to the LLM.
It is up to the caller how to present the error to the LLM, e.g.:

```kotlin
"<impediment phase=\"${result.phase}\">\n${result.message}\n</impediment>"
```

## Security

The snippets run with the full privileges of the host application, there is no sandbox.
It is compiled against the whole classpath, and executed in the JVM of the host,
so it can do whatever the host can:
read and write files, open network connections, read the environment variables and system properties,
access the classes and singletons of the host through reflection,
start processes, or terminate the JVM with `System.exit`.
The provided properties limit what the script is given, not what it can reach.

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
