/*
 * Copyright 2014 Google Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.acai;

import com.google.acai.TestScope.TestScopeModule;
import com.google.acai.TestingServiceModule.NoopTestingServiceModule;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.Module;
import org.junit.rules.MethodRule;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.Statement;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Acai rule for integrating Guice with a JUnit4 test.
 *
 * <p>Use to inject a test with a module:
 * <pre>
 *   public class MyTest {
 *     {@literal @}Rule Acai acai = new Acai(MyModule.class);
 *
 *     {@literal @}Inject MyClass willBeInjectedUsingMyModule;
 *
 *     {@literal @}Test
 *     public void testSomething() {
 *       // Your test goes here.
 *     }
 *   }
 * </pre>
 *
 * <p>To configure services to run before or between tests see {@link TestingService} and
 * {@link TestingServiceModule}.
 *
 * <p>See the {@code README.md} file for detailed usage examples.
 */
public class Acai implements MethodRule {
  private static final Map<Class<? extends Module>, TestEnvironment> environments = new HashMap<>();
  private final Class<? extends Module> module;

  public Acai(Class<? extends Module> module) {
    this.module = checkNotNull(module);
  }

  @Override
  public Statement apply(final Statement statement, FrameworkMethod frameworkMethod,
      final Object target) {
    return new Statement() {
      @Override public void evaluate() throws Throwable {
        TestEnvironment testEnvironment = getOrCreateTestEnvironment(module);
        testEnvironment.beforeSuiteIfNotAlreadyRun();
        testEnvironment.beforeTest();
        testEnvironment.inject(target);
        try {
          statement.evaluate();
        } finally {
          testEnvironment.afterTest();
        }
      }
    };
  }

  /**
   * Returns the {@code TestEnvironment} for the module, creating it if this
   * is the first time it has been requested.
   */
  private static TestEnvironment getOrCreateTestEnvironment(Class<? extends Module> module) {
    if (environments.containsKey(module)) {
      return environments.get(module);
    }
    Injector injector = Guice.createInjector(
        instantiateModule(module), new NoopTestingServiceModule(), new TestScopeModule());
    TestEnvironment testEnvironment = new TestEnvironment(injector,
        Iterables.transform(
            Dependencies.inOrder(
                injector.getInstance(new Key<Set<TestingService>>(AcaiInternal.class) {})),
            TestingServiceManager.createFunction()));
    environments.put(module, testEnvironment);
    return testEnvironment;
  }

  /**
   * Instantiates the module from its class ignoring visibility restrictions.
   *
   * <p>Will fail if the module does not have a zero argument constructor.
   */
  private static Module instantiateModule(Class<? extends Module> module) {
    try {
      Constructor<? extends Module> constructor = module.getDeclaredConstructor();
      constructor.setAccessible(true);
      return constructor.newInstance();
    } catch (NoSuchMethodException e) {
      throw new RuntimeException(
          "Module provided by user does not have zero argument constructor.");
    } catch (InvocationTargetException | InstantiationException | IllegalAccessException e) {
      throw Throwables.propagate(e);
    }
  }

  /**
   * Resets the static environment map.
   *
   * <p>For use in unit tests of Acai itself.
   */
  @VisibleForTesting static void testOnlyResetEnvironments() {
    environments.clear();
  }

  /**
   * A TestEnvironment represents a configuration for running tests derived
   * from a single Guice module.
   */
  private static class TestEnvironment {
    private final Injector injector;
    private final ImmutableList<TestingServiceManager> testingServices;
    private final AtomicBoolean beforeSuiteHasRun = new AtomicBoolean(false);
    private final TestScope testScope;

    /**
     * Initializes a newly created {@code TestEnvironment} with an {@code injector} and
     * a list of {@code testingServices} in the order they will be executed.
     */
    TestEnvironment(Injector injector, Iterable<TestingServiceManager> testingServices) {
      this.injector = checkNotNull(injector);
      this.testingServices = ImmutableList.copyOf(testingServices);
      this.testScope = injector.getInstance(Key.get(TestScope.class, AcaiInternal.class));
    }

    void inject(Object target) {
      injector.injectMembers(target);
    }

    void beforeSuiteIfNotAlreadyRun() {
      if (beforeSuiteHasRun.getAndSet(true)) {
        return;
      }
      for (TestingServiceManager testingService : testingServices) {
        testingService.beforeSuite();
      }
    }

    void beforeTest() {
      testScope.enter();
      for (TestingServiceManager testingService : testingServices) {
        testingService.beforeTest();
      }
    }

    void afterTest() {
      try {
        for (TestingServiceManager testingService : testingServices.reverse()) {
          testingService.afterTest();
        }
      } finally {
        testScope.exit();
      }
    }
  }
}
