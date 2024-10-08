/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zeppelin.scheduler;

import org.apache.zeppelin.interpreter.AbstractInterpreterTest;
import org.apache.zeppelin.interpreter.InterpreterContext;
import org.apache.zeppelin.interpreter.InterpreterException;
import org.apache.zeppelin.interpreter.InterpreterSetting;
import org.apache.zeppelin.interpreter.remote.RemoteInterpreter;
import org.apache.zeppelin.resource.LocalResourcePool;
import org.apache.zeppelin.scheduler.Job.Status;
import org.apache.zeppelin.user.AuthenticationInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteSchedulerTest extends AbstractInterpreterTest {
  private static final Logger LOGGER = LoggerFactory.getLogger(RemoteSchedulerTest.class);
  private InterpreterSetting interpreterSetting;
  private SchedulerFactory schedulerSvc;
  private static final int TICK_WAIT = 100;
  private static final int MAX_WAIT_CYCLES = 100;
  private String note1Id;

  @Override
  @BeforeEach
  public void setUp() throws Exception {
    super.setUp();
    note1Id = notebook.createNote("/note_1", AuthenticationInfo.ANONYMOUS);
    schedulerSvc = SchedulerFactory.singleton();
    interpreterSetting = interpreterSettingManager.getInterpreterSettingByName("test");
  }

  @Override
  @AfterEach
  public void tearDown() {
    interpreterSetting.close();
  }

  @Test
  void test() throws Exception {
    final RemoteInterpreter intpA = (RemoteInterpreter) interpreterSetting.getInterpreter("user1", note1Id, "mock");

    intpA.open();

    Scheduler scheduler = intpA.getScheduler();

    Job<Object> job = new Job<Object>("jobId", "jobName", null) {
      Object results;

      @Override
      public Object getReturn() {
        return results;
      }

      @Override
      public int progress() {
        return 0;
      }

      @Override
      public Map<String, Object> info() {
        return null;
      }

      @Override
      protected Object jobRun() throws Throwable {
        intpA.interpret("1000", InterpreterContext.builder()
                .setNoteId("noteId")
                .setParagraphId("jobId")
                .setResourcePool(new LocalResourcePool("pool1"))
                .build());
        return "1000";
      }

      @Override
      protected boolean jobAbort() {
        return false;
      }

      @Override
      public void setResult(Object results) {
        this.results = results;
      }
    };
    scheduler.submit(job);

    int cycles = 0;
    while (!job.isRunning() && cycles < MAX_WAIT_CYCLES) {
      LOGGER.info("Status:" + job.getStatus());
      Thread.sleep(TICK_WAIT);
      cycles++;
    }
    assertTrue(job.isRunning());

    Thread.sleep(5 * TICK_WAIT);

    cycles = 0;
    while (!job.isTerminated() && cycles < MAX_WAIT_CYCLES) {
      Thread.sleep(TICK_WAIT);
      cycles++;
    }

    assertTrue(job.isTerminated());

    intpA.close();
    schedulerSvc.removeScheduler("test");
  }

  @Test
  void testAbortOnPending() throws Exception {
    final RemoteInterpreter intpA = (RemoteInterpreter) interpreterSetting.getInterpreter("user1", note1Id, "mock");
    intpA.open();

    Scheduler scheduler = intpA.getScheduler();

    Job<Object> job1 = new Job<Object>("jobId1", "jobName1", null) {
      Object results;
      InterpreterContext context = InterpreterContext.builder()
          .setNoteId("noteId")
          .setParagraphId("jobId1")
          .setResourcePool(new LocalResourcePool("pool1"))
          .build();

      @Override
      public Object getReturn() {
        return results;
      }

      @Override
      public int progress() {
        return 0;
      }

      @Override
      public Map<String, Object> info() {
        return null;
      }

      @Override
      protected Object jobRun() throws Throwable {
        intpA.interpret("1000", context);
        return "1000";
      }

      @Override
      protected boolean jobAbort() {
        if (isRunning()) {
          try {
            intpA.cancel(context);
          } catch (InterpreterException e) {
            e.printStackTrace();
          }
        }
        return true;
      }

      @Override
      public void setResult(Object results) {
        this.results = results;
      }
    };

    Job<Object> job2 = new Job<Object>("jobId2", "jobName2", null) {
      public Object results;
      InterpreterContext context = InterpreterContext.builder()
          .setNoteId("noteId")
          .setParagraphId("jobId2")
          .setResourcePool(new LocalResourcePool("pool1"))
          .build();

      @Override
      public Object getReturn() {
        return results;
      }

      @Override
      public int progress() {
        return 0;
      }

      @Override
      public Map<String, Object> info() {
        return null;
      }

      @Override
      protected Object jobRun() throws Throwable {
        intpA.interpret("1000", context);
        return "1000";
      }

      @Override
      protected boolean jobAbort() {
        if (isRunning()) {
          try {
            intpA.cancel(context);
          } catch (InterpreterException e) {
            e.printStackTrace();
          }
        }
        return true;
      }

      @Override
      public void setResult(Object results) {
        this.results = results;
      }
    };

    job2.setResult("result2");

    scheduler.submit(job1);
    scheduler.submit(job2);


    int cycles = 0;
    while (!job1.isRunning() && cycles < MAX_WAIT_CYCLES) {
      Thread.sleep(TICK_WAIT);
      cycles++;
    }
    assertTrue(job1.isRunning());
    assertEquals(Status.PENDING, job2.getStatus());

    job2.abort();

    cycles = 0;
    while (!job1.isTerminated() && cycles < MAX_WAIT_CYCLES) {
      Thread.sleep(TICK_WAIT);
      cycles++;
    }

    assertNotNull(job1.getDateFinished());
    assertTrue(job1.isTerminated());
    assertEquals("1000", job1.getReturn());
    assertNull(job2.getDateFinished());
    assertTrue(job2.isTerminated());
    assertEquals("result2", job2.getReturn());

    intpA.close();
    schedulerSvc.removeScheduler("test");
  }

}
