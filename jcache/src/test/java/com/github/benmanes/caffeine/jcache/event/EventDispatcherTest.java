/*
 * Copyright 2015 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.jcache.event;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import javax.cache.Cache;
import javax.cache.configuration.MutableCacheEntryListenerConfiguration;
import javax.cache.event.CacheEntryCreatedListener;
import javax.cache.event.CacheEntryEventFilter;
import javax.cache.event.CacheEntryExpiredListener;
import javax.cache.event.CacheEntryRemovedListener;
import javax.cache.event.CacheEntryUpdatedListener;

import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class EventDispatcherTest {
  @Mock Cache<Integer, Integer> cache;
  @Mock CacheEntryCreatedListener<Integer, Integer> createdListener;
  @Mock CacheEntryUpdatedListener<Integer, Integer> updatedListener;
  @Mock CacheEntryRemovedListener<Integer, Integer> removedListener;
  @Mock CacheEntryExpiredListener<Integer, Integer> expiredListener;

  CacheEntryEventFilter<Integer, Integer> allowFilter = event -> true;
  CacheEntryEventFilter<Integer, Integer> rejectFilter = event -> false;
  EventDispatcher<Integer, Integer> dispatcher;

  @BeforeMethod
  public void before() throws Exception {
    MockitoAnnotations.openMocks(this).close();
    dispatcher = new EventDispatcher<>(Runnable::run);
  }

  @AfterMethod
  public void after() throws Exception {
    dispatcher.ignoreSynchronous();
  }

  @Test
  public void register_noListener() {
    var configuration =
        new MutableCacheEntryListenerConfiguration<Integer, Integer>(null, null, false, false);
    dispatcher.register(configuration);
    assertThat(dispatcher.dispatchQueues.keySet()).isEmpty();
  }

  @Test
  public void deregister() {
    var configuration = new MutableCacheEntryListenerConfiguration<>(
        () -> createdListener, null, false, false);
    dispatcher.register(configuration);
    dispatcher.deregister(configuration);
    assertThat(dispatcher.dispatchQueues.keySet()).isEmpty();
  }

  @Test
  public void publishCreated() {
    registerAll();
    dispatcher.publishCreated(cache, 1, 2);
    verify(createdListener, times(4)).onCreated(any());
    assertThat(EventDispatcher.pending.get()).hasSize(2);
  }

  @Test
  public void publishUpdated() {
    registerAll();
    dispatcher.publishUpdated(cache, 1, 2, 3);
    verify(updatedListener, times(4)).onUpdated(any());
    assertThat(EventDispatcher.pending.get()).hasSize(2);
  }

  @Test
  public void publishRemoved() {
    registerAll();
    dispatcher.publishRemoved(cache, 1, 2);
    verify(removedListener, times(4)).onRemoved(any());
    assertThat(EventDispatcher.pending.get()).hasSize(2);
  }

  @Test
  public void publishExpired() {
    registerAll();
    dispatcher.publishExpired(cache, 1, 2);
    verify(expiredListener, times(4)).onExpired(any());
    assertThat(EventDispatcher.pending.get()).hasSize(2);
  }

  @Test(threadPoolSize = 5, invocationCount = 25)
  public void concurrent() {
    dispatcher.publishCreated(cache, 1, 2);
    dispatcher.publishUpdated(cache, 1, 2, 3);
    dispatcher.publishRemoved(cache, 1, 2);
    dispatcher.publishExpired(cache, 1, 2);
  }

  @Test
  public void awaitSynchronous() {
    EventDispatcher.pending.get().add(CompletableFuture.completedFuture(null));
    dispatcher.awaitSynchronous();
    assertThat(EventDispatcher.pending.get()).isEmpty();
  }

  @Test
  public void awaitSynchronous_failure() {
    var future = new CompletableFuture<Void>();
    future.completeExceptionally(new RuntimeException());
    EventDispatcher.pending.get().add(future);
    dispatcher.awaitSynchronous();
    assertThat(EventDispatcher.pending.get()).isEmpty();
  }

  @Test
  public void ignoreSynchronous() {
    EventDispatcher.pending.get().add(CompletableFuture.completedFuture(null));
    dispatcher.ignoreSynchronous();
    assertThat(EventDispatcher.pending.get()).isEmpty();
  }

  /**
   * Registers (4 listeners) * (2 synchronous modes) * (3 filter modes) = 24 configurations. For
   * simplicity, an event is published and ignored if the listener is of the wrong type. For a
   * single event, it should be consumed by (2 filter) * (2 synchronous) = 4 listeners where only
   * 2 are synchronous.
   */
  private void registerAll() {
    var listeners = List.of(createdListener, updatedListener, removedListener, expiredListener);
    for (var listener : listeners) {
      for (boolean synchronous : List.of(true, false)) {
        dispatcher.register(new MutableCacheEntryListenerConfiguration<>(
            () -> listener, null, false, synchronous));
        dispatcher.register(new MutableCacheEntryListenerConfiguration<>(
            () -> listener, () -> allowFilter, false, synchronous));
        dispatcher.register(new MutableCacheEntryListenerConfiguration<>(
            () -> listener, () -> rejectFilter, false, synchronous));
      }
    }
  }
}
