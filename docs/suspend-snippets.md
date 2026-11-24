# Suspending snippets

The design of snippets calling suspend functions directly,
like `val page = client.get(url).bodyAsText()`,
without wrapping them in `await { }`,
while the session stays a persistent REPL.

## Why

The snippets are written by an LLM agent.
They reach the internet with a ktor client,
and talk to the execution environment through suspend functions provided to the session,
so nearly every line of a snippet suspends.
Blocking the thread of the session on each such call, as `await { }` did,
made the agent learn a second way of calling suspend functions,
and made the cancellation of a snippet depend on interrupting its thread.

## What was tried before

Before the K2 REPL compiler, a snippet was a regular Kotlin script,
and its body was rewritten into a suspend lambda (`ScriptSource`, removed in a061fd4).
Moving the body into a lambda turned its top-level declarations into local ones,
which changed their meaning in many ways — forward references, `inline` and `const` declarations, visibility, overloads, extensions —
each repaired by analysing the PSI of the script and by confirming compilations.
That list never ended: `ScriptSource.kt` alone grew to 5,682 lines.

## How it works now

The K2 REPL compiler compiles a snippet into a class,
with the top-level declarations of the snippet as members of that class,
and only its statements in one synthetic function, `$$eval`,
of the origin `FirDeclarationOrigin.Synthetic.ReplEvalFunction`.
The property initializers are resolved as statements of `$$eval` as well (`transformReplPropertyInitializer`).

So it is enough to mark `$$eval` `suspend`,
by a `FirStatusTransformerExtension` of a compiler plugin (`SuspendSnippetStatusTransformer`),
loaded by `-Xplugin`, like the serialization plugin.
The resolution, the checkers and the coroutine codegen of the compiler then treat the body like the body of any other suspend function,
while the declarations keep their meaning, as nothing is moved.

An IR plugin alone cannot do it:
calling a suspend function from a function which is not `suspend` is a FIR diagnostic (`ILLEGAL_SUSPEND_FUNCTION_CALL`),
reported before any IR is generated.

The plugin lives in its own module, `xemantic-kotlin-script-compiler-plugin`,
as `-Xplugin` takes the roots of a plugin, which the compiler scans for the `CompilerPluginRegistrar` service,
a jar, or, in the build of this project, the directories of its classes and of its resources, which differ, see `suspendSnippetPluginRoots`.
Its classes are loaded parent-first, from the classloader of the compiler embedded in the host.
Without `-Xplugin`, the scripting compiler would load every plugin of the classpath of the host,
so the session lists both of its plugins, this one and the serialization one.

The `K2ReplEvaluator` of Kotlin 2.4 and 2.5 calls `$$eval` without a continuation,
so the session evaluates the snippets itself, see `SnippetEvaluator`.
It calls `$$eval` with a continuation of the context of the caller, but with the job of the evaluation, see `SnippetEvaluation`,
so cancelling the caller, or closing the session, reaches the snippet at its next suspension point,
while the event loop of the `runBlocking` of the thread of the session resumes it there.

## What the probe showed

A probe of Kotlin 2.4.21 and 2.5.0-RC compiled and ran all of these as regular Kotlin code:

* `delay` at the top level, suspend calls in property initializers, in destructuring declarations, and in the expressions of delegates;
* suspension in inline lambdas, like `map { delay(1); it }`, in string templates, and in `for` loops over suspend iterators;
* loops, `break`, and `try` / `finally` across suspension points, and many locals spilled across one;
* `lateinit`, `const val`, enums, type aliases, value class results, `@Serializable` classes;
* suspend functions, and classes, of earlier snippets;
* `coroutineScope { }` with `async`, `withTimeout`, `yield`, `sequence { }`;
* the implicit types of the properties initialized by suspend calls, read by functions and classes of the same snippet;
* returning to the thread of the session after `withContext(Dispatchers.IO)`;
* cooperative cancellation: a snippet cancelled while suspended keeps the declarations it initialized, and leaves the later ones uninitialized, like a snippet throwing an exception.

The compiler still rejects what Kotlin rejects:
a suspend call in `lazy { }`, in `Runnable { }`, or within `synchronized`.

## Corner cases

* **The `coroutineContext` of the implicit receiver.**
  At the top level of a snippet, `coroutineContext`, `isActive` and `ensureActive()` resolve to the `ScriptScope`, living as long as the session,
  not to the coroutine of the snippet, so a `while (isActive)` loop would never see its caller cancelled.
  `ScriptScope` declares `isActive` and `ensureActive()` as members reading the snippet being evaluated, which win over the extensions of `CoroutineScope`,
  while within `launch { }` the extensions of the scope of the lambda still win, see `should tell coroutine launched by snippet active by its own scope after the snippet`,
  and `coroutineContext` stays the one of the session, which `launch` needs, see the decisions below.
  A function of a snippet reading `isActive`, called by a coroutine launched by a snippet, reads the snippet being evaluated then, if any, or the session.
* **The stack traces.**
  A snippet, or a suspend function of a snippet, resumed after a suspension has a frame of its continuation, like `Snippet1$$$eval$1.invokeSuspend(snippet1.kts)`, without a line,
  which the report omits, and no frame of the evaluator below it, nor of the code which called it before the suspension.
* **The inlined frames.**
  `$$eval` becomes a state machine, splitting the ranges of the inline markers, which `InlinedFrames` handles already for the suspend functions of the snippets —
  its tests call the suspend functions from the body of a snippet now.
* **The deeper stack.**
  A suspend function takes more of the stack than a regular one: a recursion 10,000 deep overflowed a default stack in the probe, while the thread of the session has 16 MB.
* **Blocking calls.**
  A blocking call, like `Thread.sleep`, is not cut short by cancelling the snippet any more, see the decisions below.
* **The internal API.**
  The plugin relies on the origin `ReplEvalFunction`, and the evaluator on the layout of the snippet class (`INSTANCE`, `$$eval`, the result field),
  internal details of the K2 REPL compiler, re-checked after every Kotlin upgrade.

## Decisions

* **The body of a snippet is a coroutine of the caller of `execute`.**
  It runs in the context of the caller, with the job of the evaluation as a child of the caller's job,
  under `runBlocking` on the thread of the session, so a thread-bound API stays on that thread across suspensions, and the turns of the callers stay ordered as before.
* **Cancellation is cooperative only.**
  Cancelling the caller of `execute`, or closing the session, cancels the job of the evaluation, which the snippet sees at its next suspension point.
  The thread of the session is never interrupted, which an interrupt of the parked `runBlocking` would turn into an `InterruptedException` of `execute`,
  and which might close an interruptible NIO channel of the compiler.
  A snippet blocked in a non-suspending call keeps running until it returns — the agent is expected to call suspend functions, like `delay` instead of `Thread.sleep`.
* **The implicit receiver is the supervisor scope of the session.**
  `launch { }` and `async { }` of a snippet start the coroutines outliving it, supervised by the session, so a failing one does not fail the session.
  `isActive` and `ensureActive()` of the receiver tell about the snippet being evaluated.
* **`await { }` is removed**, as calling a suspend function directly does the same, and two ways of doing it only mislead the agent.

## Kotlin 2.5

The suspending snippets are built on Kotlin 2.5, which:

* resolves a declaration of an earlier snippet in a declaration of an inferred type, so the hints at declaring the type are gone;
* compiles reading a `const val`, or a `@JvmField` property, of an earlier snippet, failing at runtime instead;
* rolls back the history of a snippet which failed to compile itself (`FirReplHistoryProvider.removeSnippets`), so `ReplHistory` is gone;
* moved `K2ReplEvaluator` to `kotlin.script.experimental.jvm`;
* reports `error: Incomplete code`, without a location, before a syntax error at the end of a snippet;
* breaks a reference to a function of an earlier snippet, like `map(::shout)`, which fails with a `ClassCastException` to `Void` when called, also with the type declared,
  while 2.4 called it with the type declared — a regression of 2.5.0-RC, hinted at by calling the function from a lambda instead, worth reporting to JetBrains,
  and re-checking on 2.5.0.
