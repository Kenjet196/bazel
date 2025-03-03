// Copyright 2018 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.skyframe.serialization;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ClassToInstanceMap;
import com.google.common.collect.ImmutableClassToInstanceMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.skyframe.serialization.Memoizer.Serializer;
import com.google.devtools.build.lib.skyframe.serialization.SerializationException.NoCodecException;
import com.google.errorprone.annotations.CheckReturnValue;
import com.google.protobuf.CodedOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Stateful class for providing additional context to a single serialization "session". This class
 * is thread-safe so long as {@link #serializer} is null (which also implies that {@link
 * #allowFuturesToBlockWritingOn}) is false). If it is not null, this class is not thread-safe and
 * should only be accessed on a single thread for serializing one object (that may involve
 * serializing other objects contained in it).
 */
public class SerializationContext implements SerializationDependencyProvider {
  private final ObjectCodecRegistry registry;
  private final ImmutableClassToInstanceMap<Object> dependencies;
  @Nullable private final Memoizer.Serializer serializer;
  private final Set<Class<?>> explicitlyAllowedClasses;
  /** Initialized lazily. */
  @Nullable private List<ListenableFuture<Void>> futuresToBlockWritingOn;

  private final boolean allowFuturesToBlockWritingOn;

  private SerializationContext(
      ObjectCodecRegistry registry,
      ImmutableClassToInstanceMap<Object> dependencies,
      @Nullable Serializer serializer,
      boolean allowFuturesToBlockWritingOn) {
    this.registry = registry;
    this.dependencies = dependencies;
    this.serializer = serializer;
    this.allowFuturesToBlockWritingOn = allowFuturesToBlockWritingOn;
    explicitlyAllowedClasses = serializer != null ? new HashSet<>() : ImmutableSet.of();
  }

  @VisibleForTesting
  public SerializationContext(
      ObjectCodecRegistry registry, ImmutableClassToInstanceMap<Object> dependencies) {
    this(registry, dependencies, /*serializer=*/ null, /*allowFuturesToBlockWritingOn=*/ false);
  }

  @VisibleForTesting
  public SerializationContext(ImmutableClassToInstanceMap<Object> dependencies) {
    this(AutoRegistry.get(), dependencies);
  }

  // TODO(shahan): consider making codedOut a member of this class.
  public void serialize(Object object, CodedOutputStream codedOut)
      throws IOException, SerializationException {
    ObjectCodecRegistry.CodecDescriptor descriptor =
        recordAndGetDescriptorIfNotConstantMemoizedOrNull(object, codedOut);
    if (descriptor == null) {
      return;
    }
    @SuppressWarnings("unchecked")
    ObjectCodec<Object> castCodec = (ObjectCodec<Object>) descriptor.getCodec();
    if (serializer == null) {
      castCodec.serialize(this, object, codedOut);
      return;
    }
    serializer.serialize(this, object, castCodec, codedOut);
  }

  @Override
  public <T> T getDependency(Class<T> type) {
    return checkNotNull(dependencies.getInstance(type), "Missing dependency of type %s", type);
  }

  /**
   * Returns a {@link SerializationContext} that will memoize values it encounters (using reference
   * equality) in a new memoization table. The returned context should be used instead of the
   * original: memoization may only occur when using the returned context. Calls must be in pairs
   * with {@link DeserializationContext#getMemoizingContext} in the corresponding deserialization
   * code.
   *
   * <p>This method is idempotent: calling it on an already memoizing context will return the same
   * context.
   */
  @CheckReturnValue
  public SerializationContext getMemoizingContext() {
    if (serializer != null) {
      return this;
    }
    return getNewMemoizingContext(/*allowFuturesToBlockWritingOn=*/ false);
  }

  /**
   * Returns a {@link SerializationContext} that will memoize values as described in {@link
   * #getMemoizingContext} and additionally permits attaching futures through {@link
   * #addFutureToBlockWritingOn}.
   */
  @CheckReturnValue
  public SerializationContext getMemoizingAndBlockingOnWriteContext() {
    checkState(serializer == null, "Should only be called on base serializationContext");
    checkState(!allowFuturesToBlockWritingOn, "Should only be called on base serializationContext");
    return getNewMemoizingContext(/*allowFuturesToBlockWritingOn=*/ true);
  }

  /**
   * Returns a memoizing {@link SerializationContext}, as getMemoizingContext above. Unlike
   * getMemoizingContext, this method is not idempotent - the returned context will always be fresh.
   */
  public SerializationContext getNewMemoizingContext() {
    return getNewMemoizingContext(allowFuturesToBlockWritingOn);
  }

  private SerializationContext getNewMemoizingContext(boolean allowFuturesToBlockWritingOn) {
    return new SerializationContext(
        registry, dependencies, new Memoizer.Serializer(), allowFuturesToBlockWritingOn);
  }

  /**
   * Returns a new {@link SerializationContext} mostly identical to this one, but with a dependency
   * map composed by applying overrides to this context's dependencies.
   *
   * <p>The given {@code dependencyOverrides} may contain keys already present (in which case the
   * dependency will be replaced) or new keys (in which case the dependency will be added).
   *
   * <p>Must only be called on a base context (no memoization state), since changing dependencies
   * may change deserialization semantics.
   */
  @CheckReturnValue
  public SerializationContext withDependencyOverrides(ClassToInstanceMap<?> dependencyOverrides) {
    checkState(serializer == null, "Must only be called on base SerializationContext");
    return new SerializationContext(
        registry,
        ImmutableClassToInstanceMap.builder()
            .putAll(Maps.filterKeys(dependencies, k -> !dependencyOverrides.containsKey(k)))
            .putAll(dependencyOverrides)
            .build(),
        /*serializer=*/ null,
        allowFuturesToBlockWritingOn);
  }

  /**
   * Registers a {@link ListenableFuture} that must complete successfully before the serialized
   * bytes generated using this context can be written remotely.
   */
  public void addFutureToBlockWritingOn(ListenableFuture<Void> future) {
    checkState(allowFuturesToBlockWritingOn, "This context cannot block on a future");
    if (futuresToBlockWritingOn == null) {
      futuresToBlockWritingOn = new ArrayList<>();
    }
    futuresToBlockWritingOn.add(future);
  }

  /**
   * Creates a future that succeeds when all futures stored in this context via {@link
   * #addFutureToBlockWritingOn} have succeeded, or null if no such futures were stored.
   */
  @Nullable
  public ListenableFuture<Void> createFutureToBlockWritingOn() {
    return futuresToBlockWritingOn != null
        ? Futures.whenAllSucceed(futuresToBlockWritingOn).call(() -> null, directExecutor())
        : null;
  }

  /**
   * Asserts during serialization that the encoded class of this codec has been explicitly
   * whitelisted for serialization (using {@link #addExplicitlyAllowedClass}). Codecs for objects
   * that are expensive to serialize and that should only be encountered in a limited number of
   * types of {@link com.google.devtools.build.skyframe.SkyValue}s should call this method to check
   * that the object is being serialized as part of an expected {@link
   * com.google.devtools.build.skyframe.SkyValue}, like {@link
   * com.google.devtools.build.lib.packages.Package} inside {@link
   * com.google.devtools.build.lib.skyframe.PackageValue}.
   */
  public <T> void checkClassExplicitlyAllowed(Class<T> allowedClass, T objectForDebugging)
      throws SerializationException {
    if (serializer == null) {
      throw new SerializationException(
          "Cannot check explicitly allowed class "
              + allowedClass
              + " without memoization ("
              + objectForDebugging
              + ")");
    }
    if (!explicitlyAllowedClasses.contains(allowedClass)) {
      throw new SerializationException(
          allowedClass
              + " not explicitly allowed (allowed classes were: "
              + explicitlyAllowedClasses
              + ") and object is "
              + objectForDebugging);
    }
  }

  /**
   * Adds an explicitly allowed class for this serialization context, which must be a memoizing
   * context. Must be called by any codec that transitively serializes an object whose codec calls
   * {@link #checkClassExplicitlyAllowed}.
   *
   * <p>Normally called by codecs for {@link com.google.devtools.build.skyframe.SkyValue} subclasses
   * that know they may encounter an object that is expensive to serialize, like {@link
   * com.google.devtools.build.lib.skyframe.PackageValue} and {@link
   * com.google.devtools.build.lib.packages.Package} or {@link
   * com.google.devtools.build.lib.analysis.ConfiguredTargetValue} and {@link
   * com.google.devtools.build.lib.analysis.configuredtargets.RuleConfiguredTarget}.
   *
   * <p>In case of an unexpected failure from {@link #checkClassExplicitlyAllowed}, it should first
   * be determined if the inclusion of the expensive object is legitimate, before it is whitelisted
   * using this method.
   */
  public void addExplicitlyAllowedClass(Class<?> allowedClass) throws SerializationException {
    if (serializer == null) {
      throw new SerializationException(
          "Cannot add explicitly allowed class %s without memoization: " + allowedClass);
    }
    explicitlyAllowedClasses.add(allowedClass);
  }

  private boolean writeNullOrConstant(@Nullable Object object, CodedOutputStream codedOut)
      throws IOException {
    if (object == null) {
      codedOut.writeSInt32NoTag(0);
      return true;
    }
    Integer tag = registry.maybeGetTagForConstant(object);
    if (tag != null) {
      codedOut.writeSInt32NoTag(tag);
      return true;
    }
    return false;
  }

  @Nullable
  private ObjectCodecRegistry.CodecDescriptor recordAndGetDescriptorIfNotConstantMemoizedOrNull(
      @Nullable Object object, CodedOutputStream codedOut) throws IOException, NoCodecException {
    if (writeNullOrConstant(object, codedOut)) {
      return null;
    }
    if (serializer != null) {
      int memoizedIndex = serializer.getMemoizedIndex(object);
      if (memoizedIndex != -1) {
        // Subtract 1 so it will be negative and not collide with null.
        codedOut.writeSInt32NoTag(-memoizedIndex - 1);
        return null;
      }
    }
    ObjectCodecRegistry.CodecDescriptor descriptor = registry.getCodecDescriptorForObject(object);
    codedOut.writeSInt32NoTag(descriptor.getTag());
    return descriptor;
  }
}
