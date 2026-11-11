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
- A test that an object is garbage collected must not create it within `runTest`: a frame of its coroutine can keep a stale reference to it, so the test fails without a leak —
  see `should release closed session with its dependencies`.
- Keep a function reference which kotlin-reflect cannot resolve, like of a local function or of `Integer::toHexString`, out of an `assert(expr)` expression:
  power-assert renders it by its `toString`, which resolves the function and throws `KotlinReflectionInternalError`, hiding the actual failure.
- Never assert on wall-clock time (a ratio or a bound): tests run under whatever CI load there is, and fail with no code change.
  To guard against, say, quadratic backtracking, feed an input large enough that the regression hangs for minutes while the correct code takes milliseconds.

### Working with AI agents

- In Claude Code "auto mode", never commit on your own — leave the changes in the working tree so the developer can review the diff first, and commit only when explicitly asked.
- The copyright year range (e.g. 2025-2026) is applied on autosave — a new file uses only the current year.

## Known gotchas

- After upgrading the Gradle wrapper, `test` may fail with `NoSuchFileException: build/test-results/test/binary/in-progress-results-generic.bin`, because the results of the previous Gradle version are stale — delete `build/test-results` (or run `clean`) and retry.
- After a fresh Gradle daemon starts, the first build reports `configuration cache cannot be reused because file '.../caches/jreleaser/jreleaser/<version>/marker.txt' has changed` —
  the JReleaser plugin bumps a banner counter in that file behind Gradle's back on every configuration,
  so the cache is invalidated once per daemon lifetime and reused normally afterwards; it is harmless.
- The Gradle configuration cache is enabled (`gradle.properties`), so a task action (`doLast { … }`) must not call a function declared in the build script — the script object is not serializable.
  Inline the logic or move the helper into `buildSrc` / an included build, and register tasks with `tasks.register(...)` rather than the `by tasks.registering` delegate.
- Expected compilation error messages in the tests are compiler-version specific, re-check them after every Kotlin upgrade.
- A ktor test server serving Maven Resolver must answer `HEAD` without the body (`AutoHeadResponse`, not a `route { handle { } }` of any method) —
  its pooled HTTP client reads the body as the next response on the keep-alive connection and stalls for tens of seconds,
  so a test times out under load only — see `should answer HEAD request without body on keep-alive connection`.
- A `VirtualMachineError`, like an `OutOfMemoryError`, escaping a test does not fail it, but aborts the whole Gradle test executor (`Could not complete execution for Gradle Test Executor`) —
  catch it with `runCatching` and assert on it, also where only a regression would let it escape.
- When the build fails on an `org.apache.maven(.resolver)` artifact requested in another version than the catalog's, after a Kotlin upgrade,
  `./gradlew dependencies --configuration runtimeClasspath | grep -E 'maven-resolver-api|maven-core'` shows the versions Kotlin brings now — the failure message names only the requested one.
  Never pin a newer Maven Resolver instead: a pin lifts only its own artifacts, so the API of 2.x meets the implementation of 1.9 on the classpath, which works by luck until a `NoSuchMethodError`.
  Once Kotlin brings Resolver 2.x, re-check `isUnauthorized`.

### The K2 REPL compiler (`K2ReplCompiler`)

- `K2ReplCompiler.createCompilationState` must get a host configuration with `repl { firReplHistoryProvider(...); isReplSnippetSource { _, _ -> true } }` —
  passing the plain `defaultJvmScriptingHostConfiguration` crashes the first snippet with an NPE in `Fir2IrReplSnippetConfiguratorExtensionImpl`.
- Its own history provider keeps the snippets which failed to compile, so the next snippet resolves their declarations, and fails at runtime — see `ReplHistory`.
- `compile` drops the warnings of a successful snippet (`asSuccess()` without reports), they are read from the message collector instead.
- `compile` may throw instead of returning a failure, e.g. on a backend crash, also an `Error`, like a `StackOverflowError` of the parser on deeply nested parentheses.
  The history provider gets the snippet only after its FIR resolution, so an overflow of the parser or of the resolution does not put it there.
- `providedProperties` are not resolved by the K2 REPL — the dependencies are declared by a generated first snippet instead.
- The snippet classes carry no nullability annotations (`@NotNull`), unlike regular Kotlin classes, so the nullability of a property has to be read by kotlin-reflect — see `UninitializedProperties.keep`.
- kotlin-reflect reads a `private` or `internal` member of a snippet class as `PUBLIC` — see `SnippetDeclarations.isAccessibleByLaterSnippet`.
- The evaluator evaluates every compiled snippet not evaluated yet before the given one, so a compiled snippet is always evaluated, even if the caller was cancelled meanwhile.
- Never interrupt the thread while compiling: an interrupt closes any interruptible NIO channel in use (JDK behaviour), which the compiler's JAR reading might rely on — a precaution, not reproduced.
  An interrupted `runBlocking` (kotlinx.coroutines 1.11) does not throw at once: it runs its pending events first, then cancels its coroutine and waits for it to complete —
  so a thread already interrupted still evaluates the snippet, see `should evaluate compiled snippet of caller cancelled before the evaluation`.
- A configuration refinement, like resolving `@file:DependsOn`, runs twice per snippet, and its failure does not stop the compilation:
  it continues without the refined configuration, also without the default imports, and the failure is reported without its code and end, like any warning of a refinement —
  see `SnippetDependencyResolver.takeDependencies`.
  The classpath added by a refinement stays with the compiler even if the snippet fails to compile.
- A later snippet resolves a name to its last declaration, also an `internal`, `const` or `@JvmField` one, or a `private` class, which it then fails on,
  but not to a `private` property or function, which it does not see, nor to an overload of other parameters — so a hint must check the last declaration, see `SnippetDeclarations`.
- Known compiler bugs, each with a test pinning the hint: a class of an earlier snippet is unresolved in a declaration of an inferred type calling it,
  and so is a property (also a provided one), a function, or an extension (reported `on receiver of type` on an explicit receiver) of an earlier snippet, but only when that declaration is used in the same snippet, while a property with an initializer, like `val x = name.length`, resolves it,
  while a declaration of a snippet which failed to compile stays unresolved even with the type declared, so it gets no hint,
  an `internal` property or function of an earlier snippet is called by a name it is not compiled to, failing with a `NoSuchMethodError` even with the type declared, so it gets a hint at declaring it public instead,
  a `const val` of an earlier snippet, unresolved in a declaration of an inferred type, crashes the backend wherever else a later snippet reads it (`InterpreterMethodNotFoundError`), also at the top level,
  and a `@JvmField` property is accessed as an instance field, while it is static, failing with an `IncompatibleClassChangeError` (its HotSpot message names the class, `Snippet1.limit`, which the report strips) —
  so both get a hint at declaring it again without `const` / `@JvmField`, while a `const val` of an `object` of an earlier snippet crashes as well (`Unknown function: X(O)`), without a hint,
  a value class property or function of an earlier snippet is called by a mangled name, which is not declared (the provided properties work around it with `@get:JvmName`),
  an `inline` declaration cannot be called across snippets,
  the backend crashes on `val context: Int inline get() = coroutineContext.hashCode()`, which stands in for an internal failure of the compiler in `should report internal failure of the compiler and continue the session`.
  Re-probe them after every Kotlin upgrade — the workarounds and hints might become obsolete, and the crash might stop testing anything.

### The inlined frames (`InlinedFrames`)

- The ASM relocated into `kotlin-compiler-embeddable` (`org.jetbrains.org.objectweb.asm`) lacks `Label.getOffset()`,
  so the ranges of the inline markers are compared by the instruction indices of `asm.tree`, not by bytecode offsets.
- The SMAP is parsed by the compiler's own `SMAPParser`, an internal API like the K2 REPL compiler —
  re-check it after every Kotlin upgrade, `InlinedFramesTest` and the inline function tests of `ScriptSessionTest` pin its behavior.
- A `@kotlin.internal.InlineOnly` function of the standard library, like `require` or `let`, is inlined with the lines of its call site,
  only the other inline functions, like `first { }` or `forEach { }`, get the synthetic lines beyond the end of the snippet.
  It gets no `$i$f$` marker, but its lambda argument still gets a `$i$a$-run-…` one, naming a function no enclosing marker is of.
- A `finally` block is compiled twice, for the normal and the exceptional path, with the same lines and markers of the same names —
  so are the copies of a lambda inlined twice: compare markers by name, not by identity.
- A frame tells its line, not its instruction, so when the bytecode does not tell which code of the line throws, omit the frames rather than guess:
  a wrong frame misleads more than a missing one — see `should omit intermediate frame of inline function calling the same inline function twice`.
- The state machine of a suspend function splits an inlined body into several ranges of its marker, may start its only line before the first of them,
  and puts lines of the method itself within them — a probe with a suspend inline function inlined just once misses all of it,
  see `should resolve intermediate frames of nested suspend inline functions inlined twice`.

## Anti-patterns to avoid

- Inside a lambda walking a chain of exceptions in a `Throwable` extension, like `generateSequence(this) { read(null) { cause } }`,
  an unqualified `read` binds to the outer receiver, not the lambda parameter, so every step returns the first cause —
  always qualify it, `exception.read(null) { cause }`, and test the chain at least two causes deep.
- A test of cancellation must signal "started" from within the block to be cancelled, like inside `await { }`, not before it —
  otherwise the cancellation can arrive before the block starts, so its `finally` never runs, and the test is flaky.
- Never give a private helper the name of a public method taking the lock, like an `execute` of `Repl` next to `ScriptSession.execute` —
  a slip like `execute(source)` compiles, re-enters the non-reentrant mutex, and deadlocks.
- Do not add content to this file that is already discoverable by reading the source or build scripts — that inflates context without adding signal, reducing AI agent task success rates (see [arxiv 2602.11988](https://arxiv.org/abs/2602.11988)).
