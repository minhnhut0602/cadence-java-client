/*
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */
package com.uber.cadence.internal.dispatcher;

import com.uber.cadence.workflow.CompletablePromise;
import com.uber.cadence.workflow.Promise;
import com.uber.cadence.workflow.Workflow;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.Assert.*;

public class PromiseTest {

    @Rule
    public final Tracer trace = new Tracer();

    private long currentTime;

    @Before
    public void setUp() {
        currentTime = 10;
    }

    @Test
    public void testFailure() throws Throwable {
        DeterministicRunner r = DeterministicRunner.newRunner(() -> {
            CompletablePromise<Boolean> f = Workflow.newCompletablePromise();
            trace.add("root begin");
            WorkflowInternal.newThread(false, () -> f.completeExceptionally(new IllegalArgumentException("foo"))).start();
            WorkflowInternal.newThread(false, () -> {
                try {
                    f.get();
                    trace.add("thread1 get success");
                    fail("failure expected");
                } catch (Exception e) {
                    assertEquals(IllegalArgumentException.class, e.getClass());
                    trace.add("thread1 get failure");
                }
            }).start();
            trace.add("root done");
        });
        r.runUntilAllBlocked();
        String[] expected = new String[]{
                "root begin",
                "root done",
                "thread1 get failure",
        };
        trace.setExpected(expected);
    }

    @Test
    public void testGetTimeout() throws Throwable {
        ExecutorService threadPool = new ThreadPoolExecutor(1, 1000, 1, TimeUnit.SECONDS, new SynchronousQueue<>());

        DeterministicRunner r = DeterministicRunner.newRunner(
                threadPool,
                null,
                () -> currentTime,
                () -> {
                    CompletablePromise<String> f = Workflow.newCompletablePromise();
                    trace.add("root begin");
                    WorkflowInternal.newThread(false, () -> {
                        trace.add("thread1 begin");
                        try {
                            assertEquals("bar", f.get(10, TimeUnit.SECONDS));
                            trace.add("thread1 get success");
                            fail("failure expected");
                        } catch (CancellationException e) {
                            trace.add("thread1 get cancellation");
                        } catch (TimeoutException e) {
                            trace.add("thread1 get timeout");
                            // Test default value
                        } catch (Exception e) {
                            assertEquals(IllegalArgumentException.class, e.getCause().getClass());
                            trace.add("thread1 get failure");
                        }
                    }).start();
                    trace.add("root done");
                });
        r.runUntilAllBlocked();
        String[] expected = new String[]{
                "root begin",
                "root done",
                "thread1 begin",
        };
        trace.setExpected(expected);
        trace.assertExpected();

        currentTime += 11000;
        r.runUntilAllBlocked();
        expected = new String[]{
                "root begin",
                "root done",
                "thread1 begin",
                "thread1 get timeout",
        };
        trace.setExpected(expected);
        threadPool.shutdown();
        threadPool.awaitTermination(1, TimeUnit.MINUTES);
    }

    @Test
    public void testGetDefaultOnTimeout() throws Throwable {
        ExecutorService threadPool = new ThreadPoolExecutor(1, 1000, 1, TimeUnit.SECONDS, new SynchronousQueue<>());

        DeterministicRunner r = DeterministicRunner.newRunner(
                threadPool,
                null,
                () -> currentTime,
                () -> {
                    CompletablePromise<String> f = Workflow.newCompletablePromise();
                    trace.add("root begin");
                    WorkflowInternal.newThread(false, () -> {
                        trace.add("thread1 begin");
                        try {
                            assertEquals("default", f.get(10, TimeUnit.SECONDS, "default"));
                            trace.add("thread1 get success");
                        } catch (Exception e) {
                            assertEquals(IllegalArgumentException.class, e.getCause().getClass());
                            trace.add("thread1 get failure");
                        }
                    }).start();
                    trace.add("root done");
                });
        r.runUntilAllBlocked();
        String[] expected = new String[]{
                "root begin",
                "root done",
                "thread1 begin",
        };
        trace.setExpected(expected);
        trace.assertExpected();

        currentTime += 11000;
        r.runUntilAllBlocked();
        expected = new String[]{
                "root begin",
                "root done",
                "thread1 begin",
                "thread1 get success",
        };
        trace.setExpected(expected);
        threadPool.shutdown();
        threadPool.awaitTermination(1, TimeUnit.MINUTES);
    }

    @Test
    public void testTimedGetDefaultOnFailure() throws Throwable {
        ExecutorService threadPool = new ThreadPoolExecutor(1, 1000, 1, TimeUnit.SECONDS, new SynchronousQueue<>());

        DeterministicRunner r = DeterministicRunner.newRunner(
                threadPool,
                null,
                () -> currentTime,
                () -> {
                    CompletablePromise<String> f = Workflow.newCompletablePromise();
                    trace.add("root begin");
                    WorkflowInternal.newThread(false, () -> {
                        trace.add("thread1 begin");
                        try {
                            assertEquals("default", f.get(10, TimeUnit.SECONDS, "default"));
                            trace.add("thread1 get success");
                        } catch (Exception e) {
                            assertEquals(IllegalArgumentException.class, e.getCause().getClass());
                            trace.add("thread1 get failure");
                        }
                    }).start();
                    f.completeExceptionally(new RuntimeException("boo"));
                    trace.add("root done");
                });
        r.runUntilAllBlocked();
        r.runUntilAllBlocked();
        String[] expected = new String[]{
                "root begin",
                "root done",
                "thread1 begin",
                "thread1 get success",
        };
        trace.setExpected(expected);
        threadPool.shutdown();
        threadPool.awaitTermination(1, TimeUnit.MINUTES);
    }

    @Test
    public void testGetDefaultOnFailure() throws Throwable {
        ExecutorService threadPool = new ThreadPoolExecutor(1, 1000, 1, TimeUnit.SECONDS, new SynchronousQueue<>());

        DeterministicRunner r = DeterministicRunner.newRunner(
                threadPool,
                null,
                () -> currentTime,
                () -> {
                    CompletablePromise<String> f = Workflow.newCompletablePromise();
                    trace.add("root begin");
                    WorkflowInternal.newThread(false, () -> {
                        trace.add("thread1 begin");
                        try {
                            assertEquals("default", f.get("default"));
                            trace.add("thread1 get success");
                        } catch (Exception e) {
                            assertEquals(IllegalArgumentException.class, e.getCause().getClass());
                            trace.add("thread1 get failure");
                        }
                    }).start();
                    f.completeExceptionally(new RuntimeException("boo"));
                    trace.add("root done");
                });
        r.runUntilAllBlocked();
        r.runUntilAllBlocked();
        String[] expected = new String[]{
                "root begin",
                "root done",
                "thread1 begin",
                "thread1 get success",
        };
        trace.setExpected(expected);
        threadPool.shutdown();
        threadPool.awaitTermination(1, TimeUnit.MINUTES);
    }

    @Test
    public void testMultiple() throws Throwable {
        DeterministicRunner r = DeterministicRunner.newRunner(() -> {
            trace.add("root begin");
            CompletablePromise<Boolean> f1 = Workflow.newCompletablePromise();
            CompletablePromise<Boolean> f2 = Workflow.newCompletablePromise();
            CompletablePromise<Boolean> f3 = Workflow.newCompletablePromise();

            WorkflowInternal.newThread(false,
                    () -> {
                        trace.add("thread1 begin");
                        assertTrue(f1.get());
                        trace.add("thread1 f1");
                        f2.complete(true);
                        trace.add("thread1 done");
                    }
            ).start();
            WorkflowInternal.newThread(false,
                    () -> {
                        trace.add("thread2 begin");
                        assertTrue(f2.get());
                        trace.add("thread2 f2");
                        f3.complete(true);
                        trace.add("thread2 done");
                    }
            ).start();
            f1.complete(true);
            assertFalse(f1.complete(false));
            trace.add("root before f3");
            assertTrue(f3.get());
            trace.add("root done");
        });
        r.runUntilAllBlocked();
        String[] expected = new String[]{
                "root begin",
                "root before f3",
                "thread1 begin",
                "thread1 f1",
                "thread1 done",
                "thread2 begin",
                "thread2 f2",
                "thread2 done",
                "root done"
        };

        trace.setExpected(expected);
    }

    @Test
    public void testAllOf() throws Throwable {
        DeterministicRunner r = DeterministicRunner.newRunner(() -> {
            trace.add("root begin");
            CompletablePromise<String> f1 = Workflow.newCompletablePromise();
            CompletablePromise<String> f2 = Workflow.newCompletablePromise();
            CompletablePromise<String> f3 = Workflow.newCompletablePromise();

            WorkflowInternal.newThread(false,
                    () -> {
                        trace.add("thread1 begin");
                        f1.complete("value1");
                        trace.add("thread1 done");
                    }
            ).start();
            WorkflowInternal.newThread(false,
                    () -> {
                        trace.add("thread3 begin");
                        f3.complete("value3");
                        trace.add("thread3 done");
                    }
            ).start();
            WorkflowInternal.newThread(false,
                    () -> {
                        trace.add("thread2 begin");
                        f2.complete("value2");
                        trace.add("thread2 done");
                    }
            ).start();
            List<Promise<String>> promises = new ArrayList<>();
            promises.add(f1);
            promises.add(f2);
            promises.add(f3);
            trace.add("root before allOf");
            Promise<List<String>> all = Promise.allOf(promises);
            List<String> expected = new ArrayList<>();
            expected.add("value1");
            expected.add("value2");
            expected.add("value3");
            assertEquals(expected, all.get());
            trace.add("root done");
        });
        r.runUntilAllBlocked();
        String[] expected = new String[]{
                "root begin",
                "root before allOf",
                "thread1 begin",
                "thread1 done",
                "thread3 begin",
                "thread3 done",
                "thread2 begin",
                "thread2 done",
                "root done"
        };
        trace.setExpected(expected);
    }

    @Test
    public void testAllOfArray() throws Throwable {
        DeterministicRunner r = DeterministicRunner.newRunner(() -> {
            trace.add("root begin");
            CompletablePromise<String> f1 = Workflow.newCompletablePromise();
            CompletablePromise<Integer> f2 = Workflow.newCompletablePromise();
            CompletablePromise<Boolean> f3 = Workflow.newCompletablePromise();

            WorkflowInternal.newThread(false,
                    () -> {
                        trace.add("thread1 begin");
                        f1.complete("value1");
                        trace.add("thread1 done");
                    }
            ).start();
            WorkflowInternal.newThread(false,
                    () -> {
                        trace.add("thread3 begin");
                        f3.complete(true);
                        trace.add("thread3 done");
                    }
            ).start();
            WorkflowInternal.newThread(false,
                    () -> {
                        trace.add("thread2 begin");
                        f2.complete(111);
                        trace.add("thread2 done");
                    }
            ).start();
            trace.add("root before allOf");
            assertFalse(f1.isCompleted());
            assertFalse(f2.isCompleted());
            assertFalse(f3.isCompleted());
            Promise<Void> done = Promise.allOf(f1, f2, f3);
            done.get();
            assertTrue(f1.isCompleted());
            assertTrue(f2.isCompleted());
            assertTrue(f3.isCompleted());
            trace.add("root done");
        });
        r.runUntilAllBlocked();
        String[] expected = new String[]{
                "root begin",
                "root before allOf",
                "thread1 begin",
                "thread1 done",
                "thread3 begin",
                "thread3 done",
                "thread2 begin",
                "thread2 done",
                "root done"
        };
        trace.setExpected(expected);
    }
}