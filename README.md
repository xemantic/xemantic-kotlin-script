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
val result = executor.execute(
    script = """
        import kotlinx.coroutines.delay

        delay(100)
        greeting.uppercase()
    """.trimIndent(),
    dependencies = listOf(
        dependency("greeting", "Hello World")
    )
)
when (result) {
    is ScriptResult.Value -> println(result.value) // HELLO WORLD
    is ScriptResult.Error -> println(result.message)
}
```

* The script body runs as a suspend block with a `CoroutineScope` receiver, on `Dispatchers.IO`, in the coroutine of the caller:
  the caller awaits all the coroutines the script launches, and cancelling the caller cancels the script.
* Dependencies are exposed to the script as properties of the given name and type.
* The value of the last expression of the script becomes `ScriptResult.Value.value`, or `Unit` if there is none.
* The script is compiled against the whole classpath of the current context,
  so any class available to the host application can be imported by the script.

### Error reporting

A script like:

```kotlin
asdf
println(foo)
```

produces:

```
<impediment phase="COMPILATION">
[ERROR] Unresolved reference 'asdf'.
  at line 1
  | <error>asdf</error>

[ERROR] Unresolved reference 'foo'.
  at line 2
  | println(<error>foo</error>)

</impediment>
```

Runtime exceptions are reported with the stack frames of the script only,
together with the source line of each frame:

```
<impediment phase="EVALUATION">
java.lang.Exception: must fail
  at Script.invokeSuspend(script.kts:1)
  | throw Exception("must fail")
</impediment>
```

The name of the enclosing tag can be customized with `ScriptExecutor(errorTag = "golem:impediment")`.

## Logging

The library logs through [kotlin-logging](https://github.com/oshai/kotlin-logging) over SLF4J.
Add an SLF4J provider, e.g. logback, to see the `DEBUG` logs of the compilation and evaluation phases.

## License

[GNU Affero General Public License v3.0](LICENSE)

## Development

See [DEVELOPMENT.md](DEVELOPMENT.md) for the documentation conventions,
and for maintenance notes on updating the gradle wrapper and the project dependencies.
