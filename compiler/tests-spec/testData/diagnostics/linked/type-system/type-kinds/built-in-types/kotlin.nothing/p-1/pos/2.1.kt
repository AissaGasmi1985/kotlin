// !LANGUAGE: +NewInference
// !DIAGNOSTICS: -UNUSED_VARIABLE -ASSIGNED_BUT_NEVER_ACCESSED_VARIABLE -UNUSED_VALUE -UNUSED_PARAMETER -UNUSED_EXPRESSION
// SKIP_TXT

/*
 * KOTLIN DIAGNOSTICS SPEC TEST (POSITIVE)
 *
 * SPEC VERSION: 0.1-213
 * PLACE: type-system, type-kinds, built-in-types, kotlin.nothing -> paragraph 1 -> sentence 2
 * NUMBER: 1
 * DESCRIPTION: Check of Nothing as a subtype of any type
 * HELPERS: checkType, functions
 */

// TESTCASE NUMBER: 1
class Case1() {
    val data: Nothing = TODO()
}

fun case1(c: Case1) {
    checkSubtype<Int>(c.data)
    checkSubtype<Function<Nothing>>(c.data)
}

// TESTCASE NUMBER: 2
class Case2 {
    var data: String? = null
}

fun case2(c: Case2) {
    val testValue = c.data ?: throw IllegalArgumentException("data required")
    testValue checkType { check<Nothing>() }
}

// TESTCASE NUMBER: 3
class Case3() {
    val dataFunction : Nothing = fail("fail msg")
}

fun fail(msg: String): Nothing {
    throw new Exception (msg)
}

fun case3(c: Case3) {
    c.dataFunction checkType { check<Nothing>() }
}