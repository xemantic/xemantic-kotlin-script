# CLAUDE.md

This file captures only what cannot be inferred from the codebase itself.

## Rules for editing this file

Both developers and AI agents are expected to add entries as they encounter surprises.

- **Add an entry** when you encounter something unexpected: a build quirk, a non-obvious constraint, a dependency gotcha, or any behavior that would surprise the next agent or developer.
- **Add an entry** when a developer flags an anti-pattern produced by AI — describe the anti-pattern and the preferred alternative.
- **Do not** add codebase overviews, directory listings, or anything discoverable by reading the source.
- Keep entries concise: the lesson and its non-obvious *why*, grouped under a heading if a theme emerges.
  Name a symbol or test as a pointer, but don't restate what its source comment already says.

## Conventions

### Markdown authoring

Markdown files use [semantic line breaks](https://sembr.org/):
break a line after a sentence,
and optionally at clause boundaries within a long sentence,
so that diffs stay meaningful and reviewable.

There is no column width limit —
never reflow or hard-wrap a paragraph to fit some character count.
Modern editors soft-wrap Markdown visually,
see [DEVELOPMENT.md](DEVELOPMENT.md#markdown-soft-wrapping-in-the-ide) for how to enable it.

### Tests

- **Write the failing test first.**
  For any newly found bug, edge case or use case, add a test that reproduces it *before* touching the implementation, watch it fail, then make it pass.
  Add it at the narrowest level that exercises the behavior — an end-to-end test can pass while the bug hides in an untested combination.
  Scratch tests used to probe behavior are fine, but replace them with a named regression test before finishing.
- Tests keep the `// given`, `// when`, `// then` comment structure — AI agents tend to omit it.
  In a functional-style test where the setup is inlined into the action, `// given` may be omitted, but `// when` and `// then` stay.
- Assert a boolean expression with `assert(expr)` from `com.xemantic.kotlin.test` — it is power-assert-instrumented in `build.gradle.kts`, so a failure prints every subvalue of the expression.
  Do not fall back to `assertEquals` / `assertTrue` / `assertNotNull` from `kotlin.test`, which discard that information.
- Never assert on wall-clock time (a ratio or a bound): tests run under whatever CI load there is, and fail with no code change.
  To guard against, say, quadratic backtracking, feed an input large enough that the regression hangs for minutes while the correct code takes milliseconds.

### Working with AI agents

- In Claude Code "auto mode", never commit on your own — leave the changes in the working tree so the developer can review the diff first, and commit only when explicitly asked.
- The copyright year range (e.g. 2025-2026) is applied on autosave — a new file uses only the current year.

## Known gotchas

- An `object`, an `enum class` or a nested class declared in a test script cannot access the dependencies, nor call the functions moved out of the script body, which are the members of the script instance ("captures the script class instance") —
  to test a moved declaration accessing them, use a top-level class instead, moved e.g. by making it refer to an enum, or implement a sealed interface.
  Moving a top-level property along with them does not help, not even a constant one: it becomes a member of the script instance too, and `const val` is rejected there — a plain `.kts` fails the same way.
- After upgrading the Gradle wrapper, `test` may fail with `NoSuchFileException: build/test-results/test/binary/in-progress-results-generic.bin`, because the results of the previous Gradle version are stale — delete `build/test-results` (or run `clean`) and retry.
- After a fresh Gradle daemon starts, the first build reports `configuration cache cannot be reused because file '.../caches/jreleaser/jreleaser/<version>/marker.txt' has changed` —
  the JReleaser plugin bumps a banner counter in that file behind Gradle's back on every configuration,
  so the cache is invalidated once per daemon lifetime and reused normally afterwards; it is harmless.

- The Gradle configuration cache is enabled (`gradle.properties`), so a task action (`doLast { … }`) must not call a function declared in the build script — the script object is not serializable.
  Inline the logic or move the helper into `buildSrc` / an included build, and register tasks with `tasks.register(...)` rather than the `by tasks.registering` delegate.
- Expected compilation error messages in `ScriptExecutorTest` are compiler-version specific: Kotlin 2.4 dropped the redundant "must either have an explicit type" diagnostic for `val x =` which 2.3 reported, so re-check them after every Kotlin upgrade.
- Tests run with kotlinx.coroutines debug mode (Gradle enables `-ea`), so an exception thrown by the script block arrives as a stack-trace-recovery copy wrapping the original, which `ScriptExecutor` unwraps.
  This hides bugs of the exception reporting which only show in production: test them with an exception class the recovery cannot copy, i.e. without a `(String)`, `(String, Throwable)` or `(Throwable)` constructor.
  An exception whose `getStackTrace()` contains `null` crashes the recovery itself, so such a case can only be tested by calling `ScriptErrorReporter` directly.
  The same applies to a compilation failure shared through the compilation cache: the waiters receive such a copy, while the compiling caller gets the original, so a message built from its `cause` differs between them unless unwrapped.

- The script compilation does not resolve a backticked import alias, not even a plain one like ``import kotlin.check as `verify` `` (`Unresolved reference`), while the same alias without backticks works —
  so a test needing a library function of a name with special characters, like `` `say$hi` ``, declares it in the test sources, see `StringLiteralNames.kt`, and star-imports its package.

- When probing a regular `.kts` with `BasicJvmScriptingHost` to compare the behaviour, a provided property declared as `KotlinType(typeOf<(String) -> String>())` loses its type arguments, and as `KotlinType("(String) -> String")` is unresolved —
  provide an instance of a class implementing the function type, declared as `KotlinType(ThatClass::class)`.
- In a regular `.kts`, a callable reference to a function of the script from a class of the script, like `::log`, crashes the compiler backend ("No mapping for symbol"), so there is no reference behaviour to match.

- Probe how the compiler resolves a name in the exact shape of `ScriptSource.text`, a statement `_script(...) { { … } }` with `_script` provided —
  K2 resolves differently e.g. within the initializer of a top-level `val f = { … }`, where a script member is preferred even to the extensions of a nested lambda receiver.
  Also, a moved function with an inferred return type, like `fun launch(block: () -> Unit) = "mine"`, is preferred to the `CoroutineScope` extensions of the script body, unlike one with a declared type, so a test of a reference resolved to the coroutine scope needs the declared type.

## Anti-patterns to avoid

- Inside a lambda walking a chain of exceptions in a `Throwable` extension, like `generateSequence(this) { read(null) { cause } }`,
  an unqualified `read` binds to the outer receiver, not the lambda parameter, so every step returns the first cause —
  always qualify it, `exception.read(null) { cause }`, and test the chain at least two causes deep.
- Do not add content to this file that is already discoverable by reading the source or build scripts — that inflates context without adding signal, reducing AI agent task success rates (see [arxiv 2602.11988](https://arxiv.org/abs/2602.11988)).
