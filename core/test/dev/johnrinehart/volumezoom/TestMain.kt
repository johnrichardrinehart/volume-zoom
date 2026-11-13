package dev.johnrinehart.volumezoom

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import kotlin.system.exitProcess

/** Minimal runner: no JUnit on the classpath, only kotlin-test assertions. */
fun main() {
    val tests =
        ZoomControllerTest::class
            .java
            .declaredMethods
            .filter {
                Modifier.isPublic(it.modifiers) && it.parameterCount == 0 && it.name != "setUp"
            }
            .sortedBy { it.name }
    var failed = 0
    for (test in tests) {
        val instance = ZoomControllerTest()
        try {
            instance.setUp()
            test.invoke(instance)
            println("ok   ${test.name}")
        } catch (e: InvocationTargetException) {
            failed++
            println("FAIL ${test.name}: ${e.targetException}")
        }
    }
    println("${tests.size - failed}/${tests.size} passed")
    if (failed > 0 || tests.isEmpty()) exitProcess(1)
}
