// Copyright 2016 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.bazel.repository.starlark;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Table;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.RuleDefinition;
import com.google.devtools.build.lib.bazel.repository.RepositoryResolvedEvent;
import com.google.devtools.build.lib.bazel.repository.downloader.DownloadManager;
import com.google.devtools.build.lib.bazel.repository.starlark.RepoFetchingSkyKeyComputeState.Signal;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelConstants;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.packages.BazelStarlarkContext;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.SymbolGenerator;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.pkgcache.PathPackageLocator;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.ProfilerTask;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.rules.repository.NeedsSkyframeRestartException;
import com.google.devtools.build.lib.rules.repository.RepositoryDirectoryValue;
import com.google.devtools.build.lib.rules.repository.RepositoryFunction;
import com.google.devtools.build.lib.rules.repository.WorkspaceFileHelper;
import com.google.devtools.build.lib.runtime.ProcessWrapper;
import com.google.devtools.build.lib.runtime.RepositoryRemoteExecutor;
import com.google.devtools.build.lib.skyframe.IgnoredPackagePrefixesValue;
import com.google.devtools.build.lib.skyframe.PrecomputedValue;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.build.skyframe.SkyFunction.Environment;
import com.google.devtools.build.skyframe.SkyFunctionException.Transience;
import com.google.devtools.build.skyframe.SkyKey;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Mutability;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkCallable;
import net.starlark.java.eval.StarlarkSemantics;
import net.starlark.java.eval.StarlarkThread;

/** A repository function to delegate work done by Starlark remote repositories. */
public final class StarlarkRepositoryFunction extends RepositoryFunction {
  static final String SEMANTICS = "STARLARK_SEMANTICS";

  private final DownloadManager downloadManager;
  private double timeoutScaling = 1.0;
  @Nullable private ExecutorService workerExecutorService = null;
  @Nullable private ProcessWrapper processWrapper = null;
  @Nullable private RepositoryRemoteExecutor repositoryRemoteExecutor;
  @Nullable private SyscallCache syscallCache;

  public StarlarkRepositoryFunction(DownloadManager downloadManager) {
    this.downloadManager = downloadManager;
  }

  public void setTimeoutScaling(double timeoutScaling) {
    this.timeoutScaling = timeoutScaling;
  }

  public void setProcessWrapper(@Nullable ProcessWrapper processWrapper) {
    this.processWrapper = processWrapper;
  }

  public void setSyscallCache(SyscallCache syscallCache) {
    this.syscallCache = checkNotNull(syscallCache);
  }

  public void setWorkerExecutorService(@Nullable ExecutorService workerExecutorService) {
    this.workerExecutorService = workerExecutorService;
  }

  static String describeSemantics(StarlarkSemantics semantics) {
    // Here we use the hash code provided by AutoValue. This is unique, as long
    // as the number of bits in the StarlarkSemantics is small enough. We will have to
    // move to a longer description once the number of flags grows too large.
    return "" + semantics.hashCode();
  }

  @Override
  protected boolean verifySemanticsMarkerData(Map<String, String> markerData, Environment env)
      throws InterruptedException {
    StarlarkSemantics starlarkSemantics = PrecomputedValue.STARLARK_SEMANTICS.get(env);
    if (starlarkSemantics == null) {
      // As it is a precomputed value, it should already be available. If not, returning
      // false is the safe thing to do.
      return false;
    }

    return describeSemantics(starlarkSemantics).equals(markerData.get(SEMANTICS));
  }

  @Override
  protected void setupRepoRootBeforeFetching(Path repoRoot) throws RepositoryFunctionException {
    // DON'T delete the repo root here if we're using a worker thread, since when this SkyFunction
    // restarts, fetching is still happening inside the worker thread.
    if (workerExecutorService == null) {
      setupRepoRoot(repoRoot);
    }
  }

  @Override
  public void reportSkyframeRestart(Environment env, RepositoryName repoName) {
    // DON'T report a "restarting." event if we're using a worker thread, since the actual fetch
    // function run by the worker thread never restarts.
    if (workerExecutorService == null) {
      super.reportSkyframeRestart(env, repoName);
    }
  }

  @Nullable
  @Override
  public RepositoryDirectoryValue.Builder fetch(
      Rule rule,
      Path outputDirectory,
      BlazeDirectories directories,
      Environment env,
      Map<String, String> markerData,
      SkyKey key)
      throws RepositoryFunctionException, InterruptedException {
    if (workerExecutorService == null) {
      return fetchInternal(rule, outputDirectory, directories, env, markerData, key);
    }
    var state = env.getState(RepoFetchingSkyKeyComputeState::new);
    var workerFuture = state.workerFuture;
    if (workerFuture == null) {
      // No worker is running yet, which means we're just starting to fetch this repo. Start with a
      // clean slate, and create the worker.
      setupRepoRoot(outputDirectory);
      Environment workerEnv = new RepoFetchingWorkerSkyFunctionEnvironment(state, env);
      workerFuture =
          workerExecutorService.submit(
              () -> {
                try {
                  return fetchInternal(
                      rule, outputDirectory, directories, workerEnv, state.markerData, key);
                } finally {
                  state.signalQueue.put(Signal.DONE);
                }
              });
      state.workerFuture = workerFuture;
    } else {
      // A worker is already running. This can only mean one thing -- we just had a Skyframe
      // restart, and need to send over a fresh Environment.
      state.delegateEnvQueue.put(env);
    }
    switch (state.signalQueue.take()) {
      case RESTART:
        return null;
      case DONE:
        try {
          RepositoryDirectoryValue.Builder result = workerFuture.get();
          markerData.putAll(state.markerData);
          return result;
        } catch (ExecutionException e) {
          Throwables.throwIfInstanceOf(e.getCause(), RepositoryFunctionException.class);
          Throwables.throwIfUnchecked(e.getCause());
          throw new IllegalStateException(
              "unexpected exception type: " + e.getClass(), e.getCause());
        } finally {
          // Make sure we interrupt the worker thread if work on the Skyframe thread were cut short
          // for any reason.
          state.close();
          try {
            // Synchronously wait for the worker thread to finish any remaining work.
            workerFuture.get();
          } catch (ExecutionException e) {
            // When this happens, we either already dealt with the exception (see `catch` clause
            // above), or we're in the middle of propagating an InterruptedException in which case
            // we don't care about the result of execution anyway.
          }
        }
    }
    // TODO(wyv): use a switch expression above instead and remove this.
    throw new IllegalStateException();
  }

  @Nullable
  private RepositoryDirectoryValue.Builder fetchInternal(
      Rule rule,
      Path outputDirectory,
      BlazeDirectories directories,
      Environment env,
      Map<String, String> markerData,
      SkyKey key)
      throws RepositoryFunctionException, InterruptedException {

    String defInfo = RepositoryResolvedEvent.getRuleDefinitionInformation(rule);
    env.getListener().post(new StarlarkRepositoryDefinitionLocationEvent(rule.getName(), defInfo));

    StarlarkCallable function = rule.getRuleClassObject().getConfiguredTargetFunction();
    if (declareEnvironmentDependencies(markerData, env, getEnviron(rule)) == null) {
      return null;
    }
    StarlarkSemantics starlarkSemantics = PrecomputedValue.STARLARK_SEMANTICS.get(env);
    if (env.valuesMissing()) {
      return null;
    }
    markerData.put(SEMANTICS, describeSemantics(starlarkSemantics));

    PathPackageLocator packageLocator = PrecomputedValue.PATH_PACKAGE_LOCATOR.get(env);
    if (env.valuesMissing()) {
      return null;
    }

    IgnoredPackagePrefixesValue ignoredPackagesValue =
        (IgnoredPackagePrefixesValue) env.getValue(IgnoredPackagePrefixesValue.key());
    if (env.valuesMissing()) {
      return null;
    }
    ImmutableSet<PathFragment> ignoredPatterns = checkNotNull(ignoredPackagesValue).getPatterns();

    try (Mutability mu = Mutability.create("Starlark repository")) {
      StarlarkThread thread = new StarlarkThread(mu, starlarkSemantics);
      thread.setPrintHandler(Event.makeDebugPrintHandler(env.getListener()));
      var repoMappingRecorder = new Label.RepoMappingRecorder();
      repoMappingRecorder.mergeEntries(
          rule.getRuleClassObject().getRuleDefinitionEnvironmentRepoMappingEntries());
      thread.setThreadLocal(Label.RepoMappingRecorder.class, repoMappingRecorder);

      new BazelStarlarkContext(
              BazelStarlarkContext.Phase.LOADING, // ("fetch")
              new SymbolGenerator<>(key))
          .storeInThread(thread);

      StarlarkRepositoryContext starlarkRepositoryContext =
          new StarlarkRepositoryContext(
              rule,
              packageLocator,
              outputDirectory,
              ignoredPatterns,
              env,
              ImmutableMap.copyOf(clientEnvironment),
              downloadManager,
              timeoutScaling,
              processWrapper,
              starlarkSemantics,
              repositoryRemoteExecutor,
              syscallCache,
              directories.getWorkspace());

      if (starlarkRepositoryContext.isRemotable()) {
        // If a rule is declared remotable then invalidate it if remote execution gets
        // enabled or disabled.
        PrecomputedValue.REMOTE_EXECUTION_ENABLED.get(env);
      }

      // Since restarting a repository function can be really expensive, we first ensure that
      // all label-arguments can be resolved to paths.
      try {
        starlarkRepositoryContext.enforceLabelAttributes();
      } catch (NeedsSkyframeRestartException e) {
        // Missing values are expected; just restart before we actually start the rule
        return null;
      }

      // This rule is mainly executed for its side effect. Nevertheless, the return value is
      // of importance, as it provides information on how the call has to be modified to be a
      // reproducible rule.
      //
      // Also we do a lot of stuff in there, maybe blocking operations and we should certainly make
      // it possible to return null and not block but it doesn't seem to be easy with Starlark
      // structure as it is.
      Object result;
      boolean fetchSuccessful = false;
      try (SilentCloseable c =
          Profiler.instance()
              .profile(ProfilerTask.STARLARK_REPOSITORY_FN, () -> rule.getLabel().toString())) {
        result =
            Starlark.call(
                thread,
                function,
                /*args=*/ ImmutableList.of(starlarkRepositoryContext),
                /*kwargs=*/ ImmutableMap.of());
        fetchSuccessful = true;
      } finally {
        if (starlarkRepositoryContext.ensureNoPendingAsyncTasks(
            env.getListener(), fetchSuccessful)) {
          if (fetchSuccessful) {
            throw new RepositoryFunctionException(
                new EvalException(
                    "Pending asynchronous work after repository rule finished running"),
                Transience.PERSISTENT);
          }
        }
      }

      RepositoryResolvedEvent resolved =
          new RepositoryResolvedEvent(
              rule, starlarkRepositoryContext.getAttr(), outputDirectory, result);
      if (resolved.isNewInformationReturned()) {
        env.getListener().handle(Event.debug(resolved.getMessage()));
        env.getListener().handle(Event.debug(defInfo));
      }

      // Modify marker data to include the files used by the rule's implementation function.
      for (Map.Entry<Label, String> entry :
          starlarkRepositoryContext.getAccumulatedFileDigests().entrySet()) {
        // A label does not contain spaces so it's safe to use as a key.
        markerData.put("FILE:" + entry.getKey(), entry.getValue());
      }

      // Ditto for environment variables accessed via `getenv`.
      for (String envKey : starlarkRepositoryContext.getAccumulatedEnvKeys()) {
        markerData.put("ENV:" + envKey, clientEnvironment.get(envKey));
      }

      for (Table.Cell<RepositoryName, String, RepositoryName> repoMappings :
          repoMappingRecorder.recordedEntries().cellSet()) {
        markerData.put(
            "REPO_MAPPING:"
                + repoMappings.getRowKey().getName()
                + ","
                + repoMappings.getColumnKey(),
            repoMappings.getValue().getName());
      }

      env.getListener().post(resolved);
    } catch (NeedsSkyframeRestartException e) {
      // A dependency is missing, cleanup and returns null
      try {
        if (outputDirectory.exists()) {
          outputDirectory.deleteTree();
        }
      } catch (IOException e1) {
        throw new RepositoryFunctionException(e1, Transience.TRANSIENT);
      }
      return null;
    } catch (EvalException e) {
      env.getListener()
          .handle(
              Event.error(
                  "An error occurred during the fetch of repository '"
                      + rule.getName()
                      + "':\n   "
                      + e.getMessageWithStack()));
      env.getListener()
          .handle(Event.info(RepositoryResolvedEvent.getRuleDefinitionInformation(rule)));

      throw new RepositoryFunctionException(e, Transience.TRANSIENT);
    }

    if (!outputDirectory.isDirectory()) {
      throw new RepositoryFunctionException(
          new IOException(rule + " must create a directory"), Transience.TRANSIENT);
    }

    if (!WorkspaceFileHelper.isValidRepoRoot(outputDirectory)) {
      try {
        FileSystemUtils.createEmptyFile(outputDirectory.getRelative(LabelConstants.REPO_FILE_NAME));
        if (starlarkSemantics.getBool(BuildLanguageOptions.ENABLE_WORKSPACE)) {
          FileSystemUtils.createEmptyFile(
              outputDirectory.getRelative(LabelConstants.WORKSPACE_FILE_NAME));
        }
      } catch (IOException e) {
        throw new RepositoryFunctionException(e, Transience.TRANSIENT);
      }
    }

    return RepositoryDirectoryValue.builder().setPath(outputDirectory);
  }

  @SuppressWarnings("unchecked")
  private static ImmutableSet<String> getEnviron(Rule rule) {
    return ImmutableSet.copyOf((Iterable<String>) rule.getAttr("$environ"));
  }

  /**
   * Verify marker data previously saved by {@link #declareEnvironmentDependencies(Map, Environment,
   * Set)} and/or {@link #fetchInternal(Rule, Path, BlazeDirectories, Environment, Map, SkyKey)} (on
   * behalf of {@link StarlarkBaseExternalContext#getEnvironmentValue(String, Object)}).
   */
  @Override
  protected boolean verifyEnvironMarkerData(
      Map<String, String> markerData, Environment env, Set<String> keys)
      throws InterruptedException {
    /*
     * We can ignore `keys` and instead only verify what's recorded in the marker file, because
     * any change to `keys` between builds would be caused by a change to a .bzl file, and that's
     * covered by RepositoryDelegatorFunction.DigestWriter#areRepositoryAndMarkerFileConsistent.
     */

    ImmutableSet<String> markerKeys =
        markerData.keySet().stream()
            .filter(s -> s.startsWith("ENV:"))
            .collect(ImmutableSet.toImmutableSet());

    ImmutableMap<String, String> environ =
        getEnvVarValues(
            env,
            markerKeys.stream()
                .map(s -> s.substring(4)) // ENV:FOO -> FOO
                .collect(ImmutableSet.toImmutableSet()));
    if (environ == null) {
      return false;
    }

    for (String key : markerKeys) {
      String markerValue = markerData.get(key);
      String envKey = key.substring(4); // ENV:FOO -> FOO
      if (!Objects.equals(markerValue, environ.get(envKey))) {
        return false;
      }
    }

    return true;
  }

  @Override
  protected boolean isLocal(Rule rule) {
    return (Boolean) rule.getAttr("$local");
  }

  @Override
  protected boolean isConfigure(Rule rule) {
    return (Boolean) rule.getAttr("$configure");
  }

  /**
   * Static method to determine if for a starlark repository rule {@code isConfigure} holds true. It
   * also checks that the rule is indeed a Starlark rule so that this class is the appropriate
   * handler for the given rule. As, however, only Starklark rules can be configure rules, this
   * method can also be used as a universal check.
   */
  public static boolean isConfigureRule(Rule rule) {
    return rule.getRuleClassObject().isStarlark() && ((Boolean) rule.getAttr("$configure"));
  }

  @Override
  public Class<? extends RuleDefinition> getRuleDefinition() {
    return null; // unused so safe to return null
  }

  public void setRepositoryRemoteExecutor(RepositoryRemoteExecutor repositoryRemoteExecutor) {
    this.repositoryRemoteExecutor = repositoryRemoteExecutor;
  }
}
