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
  while a snippet throwing an exception keeps the ones it declared.
* Each snippet is a regular Kotlin script, compiled by the K2 REPL compiler of the Kotlin scripting API,
  against the whole classpath of the host application,
  so any class available to the host can be imported by a snippet.
* The value of the last expression of a snippet becomes `ScriptResult.Value.value`, or `Unit` if there is none.
* All the snippets of a session are compiled and evaluated on its own thread, one after another,
  so a snippet can use an API bound to the thread which created it.
  Snippets executed concurrently wait for their turn.
  Separate sessions run in parallel, each with its own state,
  and each holding its own compiler, which loads the whole classpath, so close the sessions no longer needed.
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
* Closing the session cancels the coroutines launched by its snippets and releases its compiler and thread.
* Properties of the given name and type are provided to the snippets of the session, like `"greeting" provides "Hello"`.
  The type is inferred from the value, including its type arguments,
  or can be given explicitly, e.g. `provide<String?>("name", null)` or `provide<Clock>("clock", fixedClock)`,
  to make the property nullable, or to expose it only as its supertype.
  A property list built dynamically can be passed instead, as `ScriptSession(listOf(property("greeting", "Hello")))`.
  Invalid properties are rejected with `IllegalArgumentException`:
  a name which is not a Kotlin identifier, a duplicate name, a value which is not of the type,
  a name of a member of the `ScriptScope`, like `await` or `coroutineContext`,
  or a type which the snippets cannot refer to, like a private or a local class.
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
  A stable version with a qualifier, which Maven orders after its release, like `0.8.0-0.6.x-compat` after `0.8.0`,
  gives way to its release, if there is one.
  The LLM should be told the resolved version, as it might not know its API:
  a successful snippet returns it as `ScriptResult.Value.resolvedVersions`, like `mapOf("org.jsoup:jsoup" to "1.24.1")`,
  which, unlike the compiler warnings, is worth showing to the LLM always,
  while a failed snippet reports it in the error message,
  as a warning pointing to the `@file:DependsOn` line, like `Resolved 'org.jsoup:jsoup' to its latest stable version '1.24.1'`.
  The resolved version is kept for the session, so a later snippet declaring the dependency again does not load another version.
  The `maven-metadata.xml` is read directly, without the mirrors, proxies and credentials of the Maven settings,
  which apply only to resolving the dependency in the version found.
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
