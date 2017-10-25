/*
 * Copyright 2016-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kotlinx.coroutines.experimental

import kotlinx.coroutines.debug.test.*
import kotlinx.coroutines.debug.test.DebuggerTestAssertions.assertDebuggerPausedHereState
import org.junit.Test
import org.junit.experimental.categories.Category
import java.io.IOException

class CoroutinesTest : TestBase() {
    private val THIS = "kotlinx.coroutines.experimental.CoroutinesTest"
    private val KCE = "kotlinx.coroutines.experimental"

    @Test
    @Category(DebuggableTest::class)
    fun testSimple() = runTest {
        expect(1)
        assertDebuggerPausedHereState(coroutine("coroutine#1", Running()) {
            method("$THIS\$testSimple\$1.invoke")
            marker("coroutine#1", true)
            zeroOrMore(any)
        })
        finish(2)
    }

    @Test
    fun testYield() = runTest {
        expect(1)
        yield() // effectively does nothing, as we don't have other coroutines
        finish(2)
    }

    @Test
    @Category(DebuggableTest::class)
    fun testLaunchAndYieldJoin() = runTest {
        expect(1)
        val job = launch(coroutineContext) {
            expect(3)
            assertDebuggerPausedHereState(coroutine("coroutine#1", Suspended("$KCE.JobSupport.joinSuspend")) {
                zeroOrMore(any)
            }, coroutine("coroutine#2", Running()) {
                method("$THIS\$testLaunchAndYieldJoin\$1\$job\$1.invoke")
                marker("coroutine#2", true)
                zeroOrMore(any)
            })
            yield()
            expect(4)
        }
        expect(2)
        val current = coroutine("coroutine#1", Running()) {
            method("$THIS\$testLaunchAndYieldJoin\$1.invoke")
            marker("coroutine#1", true)
            zeroOrMore(any)
        }
        assertDebuggerPausedHereState(current, coroutine("coroutine#2", Created()))
        check(job.isActive && !job.isCompleted)
        job.join()
        check(!job.isActive && job.isCompleted)
        assertDebuggerPausedHereState(current)
        finish(5)
    }

    @Test
    fun testLaunchUndispatched() = runTest {
        expect(1)
        val job = launch(coroutineContext, start = CoroutineStart.UNDISPATCHED) {
            expect(2)
            yield()
            expect(4)
        }
        expect(3)
        check(job.isActive && !job.isCompleted)
        job.join()
        check(!job.isActive && job.isCompleted)
        finish(5)
    }

    @Test
    fun testNested() = runTest {
        expect(1)
        val j1 = launch(coroutineContext) {
            expect(3)
            val j2 = launch(coroutineContext) {
                expect(5)
            }
            expect(4)
            j2.join()
            expect(6)
        }
        expect(2)
        j1.join()
        finish(7)
    }

    @Test
    fun testWaitChild() = runTest {
        expect(1)
        launch(coroutineContext) {
            expect(3)
            yield() // to parent
            finish(5)
        }
        expect(2)
        yield()
        expect(4)
        // parent waits for child's completion
    }

    @Test
    fun testCancelChildExplicit() = runTest {
        expect(1)
        val job = launch(coroutineContext) {
            expect(3)
            yield()
            expectUnreached()
        }
        expect(2)
        yield()
        expect(4)
        job.cancel()
        finish(5)
    }

    @Test
    fun testCancelChildWithFinally() = runTest {
        expect(1)
        val job = launch(coroutineContext) {
            expect(3)
            try {
                yield()
            } finally {
                finish(6) // cancelled child will still execute finally
            }
            expectUnreached()
        }
        expect(2)
        yield()
        expect(4)
        job.cancel()
        expect(5)
    }

    @Test
    fun testWaitNestedChild() = runTest {
        expect(1)
        launch(coroutineContext) {
            expect(3)
            launch(coroutineContext) {
                expect(6)
                yield() // to parent
                expect(9)
            }
            expect(4)
            yield()
            expect(7)
            yield()  // to parent
            finish(10) // the last one to complete
        }
        expect(2)
        yield()
        expect(5)
        yield()
        expect(8)
        // parent waits for child
    }

    @Test
    fun testExceptionPropagation() = runTest(
        expected = { it is IOException }
    ) {
        finish(1)
        throw IOException()
    }

    @Test
    fun testCancelParentOnChildException() = runTest(
        expected = { it is JobCancellationException && it.cause is IOException },
        unhandled = listOf({ it -> it is IOException })
    ) {
        expect(1)
        launch(coroutineContext) {
            finish(3)
            throwIOException() // does not propagate exception to launch, but cancels parent (!)
            expectUnreached()
        }
        expect(2)
        yield()
        expectUnreached() // because of exception in child
    }

    @Test
    fun testCancelParentOnNestedException() = runTest(
        expected = { it is JobCancellationException && it.cause is IOException },
        unhandled = listOf(
            { it -> it is IOException },
            { it -> it is IOException }
        )
    ) {
        expect(1)
        launch(coroutineContext) {
            expect(3)
            launch(coroutineContext) {
                finish(6)
                throwIOException() // unhandled exception kills all parents
                expectUnreached()
            }
            expect(4)
            yield()
            expectUnreached() // because of exception in child
        }
        expect(2)
        yield()
        expect(5)
        yield()
        expectUnreached() // because of exception in child
    }

    @Test
    fun testJoinWithFinally() = runTest {
        expect(1)
        val job = launch(coroutineContext) {
            expect(3)
            try {
                yield() // to main, will cancel us
            } finally {
                expect(7) // join is waiting
            }
        }
        expect(2)
        yield() // to job
        expect(4)
        check(job.isActive && !job.isCompleted && !job.isCancelled)
        check(job.cancel())  // cancels job
        expect(5) // still here
        check(!job.isActive && !job.isCompleted && job.isCancelled)
        check(!job.cancel()) // second attempt returns false
        expect(6) // we're still here
        job.join() // join the job, let job complete its "finally" section
        expect(8)
        check(!job.isActive && job.isCompleted && job.isCancelled)
        finish(9)
    }

    @Test
    fun testCancelAndJoin() = runTest {
        expect(1)
        val job = launch(coroutineContext, CoroutineStart.UNDISPATCHED) {
            try {
                expect(2)
                yield()
                expectUnreached() // will get cancelled
            } finally {
                expect(4)
            }
        }
        expect(3)
        job.cancelAndJoin()
        finish(5)
    }

    @Test
    fun testCancelAndJoinChildCrash() = runTest(
        expected = { it is JobCancellationException && it.cause is IOException },
        unhandled = listOf({it -> it is IOException })
    ) {
        expect(1)
        val job = launch(coroutineContext, CoroutineStart.UNDISPATCHED) {
            expect(2)
            throwIOException()
            expectUnreached()
        }
        // now we have a failed job with IOException
        finish(3)
        try {
            job.cancelAndJoin() // join should crash on child's exception but it will be wrapped into JobCancellationException
        } catch (e: Throwable) {
            e as JobCancellationException // type assertion
            check(e.cause is IOException)
            check(e.job === coroutineContext[Job])
            throw e
        }
        expectUnreached()
    }

    @Test
    fun testParentCrashCancelsChildren() = runTest(
        unhandled = listOf({ it -> it is IOException })
    ) {
        expect(1)
        val parent = launch(coroutineContext + Job()) {
            expect(4)
            throw IOException("Crashed")
        }
        launch(coroutineContext + parent, CoroutineStart.UNDISPATCHED) {
            expect(2)
            try {
                yield() // to test
            } finally {
                expect(5)
                run(NonCancellable) { yield() } // to test
                expect(7)
            }
            expectUnreached() // will get cancelled, because parent crashes
        }
        expect(3)
        yield() // to parent
        expect(6)
        parent.join() // make sure crashed parent still waits for its child
        finish(8)
    }

    @Test
    fun testYieldInFinally() = runTest(
        expected = { it is IOException }
    ) {
        expect(1)
        try {
            expect(2)
            throwIOException()
        } finally {
            expect(3)
            yield()
            finish(4)
        }
        expectUnreached()
    }

    @Test
    fun testNotCancellableCodeWithExceptionCancelled() = runTest {
        expect(1)
        // CoroutineStart.ATOMIC makes sure it will not get cancelled for it starts executing
        val job = launch(start = CoroutineStart.ATOMIC) {
            Thread.sleep(100) // cannot be cancelled
            throwIOException() // will throw
            expectUnreached()
        }
        expect(2)
        job.cancel()
        finish(3)
    }

    @Test
    fun testNotCancellableChildWithExceptionCancelled() = runTest(
        expected = { it is IOException }
    ) {
        expect(1)
        // CoroutineStart.ATOMIC makes sure it will not get cancelled for it starts executing
        val d = async(coroutineContext, start = CoroutineStart.ATOMIC) {
            finish(4)
            throwIOException() // will throw
            expectUnreached()
        }
        expect(2)
        // now cancel with some other exception
        d.cancel(IllegalArgumentException())
        // now await to see how it got crashed -- IAE should have been suppressed by IOException
        expect(3)
        d.await()
    }

    private fun throwIOException() { throw IOException() }
}
