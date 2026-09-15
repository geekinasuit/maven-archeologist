// REQUIRED: Importing scripts must declare:
//   @file:Repository("https://repo1.maven.org/maven2/")
//   @file:DependsOn("org.junit.jupiter:junit-jupiter-api:5.11.0")
//   @file:DependsOn("org.junit.jupiter:junit-jupiter-engine:5.11.0")
//   @file:DependsOn("org.junit.platform:junit-platform-launcher:1.11.0")
//   @file:DependsOn("com.google.truth:truth:1.4.4")

import org.junit.jupiter.api.Test
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.engine.support.descriptor.MethodSource
import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.TestIdentifier
import org.junit.platform.launcher.TestPlan
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import org.junit.platform.launcher.listeners.SummaryGeneratingListener

object VerboseListener : TestExecutionListener {
    override fun executionStarted(id: TestIdentifier) {
        if (!id.isTest && id.parentId.isPresent) println("\n${id.displayName.substringAfterLast('$')}")
    }
    override fun executionFinished(id: TestIdentifier, result: TestExecutionResult) {
        if (!id.isTest) return
        val mark = if (result.status == TestExecutionResult.Status.SUCCESSFUL) "PASS" else "FAIL"
        val detail = result.throwable.map { " — ${it.message}" }.orElse("")
        println("  $mark  ${id.displayName}$detail")
    }
}

// INFRA-068: JUnit Jupiter SILENTLY excludes an @Test method whose return type is not
// void. The trap is the Kotlin expression-body form ending in a value-returning assertion —
// Truth's containsExactly(...) returns an Ordered, so `@Test fun x() =
// assertThat(...).containsExactly(...)` compiles, is never discovered, never runs, and the
// suite still reports green. Given the discovered TestPlan, reflect the @Test methods each
// registered class actually declares and return those JUnit dropped (as ClassName.method).
// The check is conservative — a name appears only when a declared @Test was NOT discovered —
// so extra discovered tests (@ParameterizedTest, @Nested) never make it a false positive.
// Kept pure (no I/O, no exit) and on an object, not a top-level function: a top-level fun
// in a .kts is an instance method of the script class, so a test class that calls it
// captures the script instance and JUnit can't construct it (the same silent-drop this
// guards). Static access via the object keeps the test class discoverable. (This file is
// vendored verbatim from geekinasuit/infra, where TestDiscovery is unit-tested in
// lib/TestRunner.test.main.kts; that test is not copied into this repo.) runTests adds
// the loud hard-fail.
object TestDiscovery {
    fun undiscovered(classes: Array<out Class<*>>, plan: TestPlan): List<String> {
        val discovered = HashSet<String>()
        plan.roots.forEach { root ->
            plan.getDescendants(root).forEach { id ->
                if (id.isTest) {
                    val src = id.source.orElse(null)
                    if (src is MethodSource) discovered.add("${src.className}#${src.methodName}")
                }
            }
        }
        return classes.flatMap { cls ->
            cls.declaredMethods
                .filter { it.isAnnotationPresent(Test::class.java) }
                .filter { "${cls.name}#${it.name}" !in discovered }
                .map { "${cls.simpleName}.${it.name}" }
        }
    }
}

// Printed at the top of every run so the working directory is on the record before any test
// executes. A .test.main.kts resolves its imports and any relative paths against the cwd it was
// launched from, so a suite run from the wrong checkout is a green that says nothing about the tree
// you meant to test — this line makes that visible (and survives a hang or a crash mid-run, since it
// prints first). On an object, not a top-level fun, for the same reason TestDiscovery is: a test
// class calling a top-level .kts fun captures the script instance and drops out of discovery.
object RunContext {
    fun cwdBanner(dir: String = System.getProperty("user.dir") ?: "<unknown>"): String = "TestRunner cwd: $dir"
}

fun runTests(vararg classes: Class<*>) {
    println(RunContext.cwdBanner())
    val request = LauncherDiscoveryRequestBuilder.request()
        .selectors(classes.map { selectClass(it) })
        .build()
    val launcher = LauncherFactory.create()
    val plan = launcher.discover(request)

    val missing = TestDiscovery.undiscovered(classes, plan)
    if (missing.isNotEmpty()) {
        System.err.println("\nTestRunner: ${missing.size} @Test method(s) declared but NOT discovered by JUnit.")
        System.err.println("Most often the return type is not void (an expression body ending in")
        System.err.println("containsExactly()/inOrder() etc. returns a value) — give it a void/Unit body:")
        System.err.println("use a block { } body, or drop the `=`. A private/abstract method or a")
        System.err.println("non-public test class is silently excluded too.")
        missing.sorted().forEach { System.err.println("  MISSING  $it") }
        System.exit(1)
    }

    val summary = SummaryGeneratingListener()
    launcher.execute(plan, summary, VerboseListener)
    println("\n${summary.summary.testsSucceededCount}/${summary.summary.testsStartedCount} tests passed" +
        if (summary.summary.testsFailedCount > 0L) ", ${summary.summary.testsFailedCount} FAILED" else "")
    if (summary.summary.testsFailedCount > 0L) {
        System.err.println("Test run failed.")
        System.exit(1)
    }
}
