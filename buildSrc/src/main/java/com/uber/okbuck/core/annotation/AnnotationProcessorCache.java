package com.uber.okbuck.core.annotation;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import com.google.errorprone.annotations.Var;
import com.uber.okbuck.composer.java.JavaAnnotationProcessorRuleComposer;
import com.uber.okbuck.core.dependency.DependencyUtils;
import com.uber.okbuck.core.model.base.Scope;
import com.uber.okbuck.core.util.FileUtil;
import com.uber.okbuck.core.util.LoadStatementsUtil;
import com.uber.okbuck.extension.OkBuckExtension;
import com.uber.okbuck.template.core.Rule;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;

/** Keeps a cache of the annotation processor dependencies and its scope. */
public class AnnotationProcessorCache {

  public static final String AUTO_VALUE_GROUP = "com.google.auto.value";
  public static final String AUTO_VALUE_NAME = "auto-value";

  private final Project project;
  private final OkBuckExtension extension;
  private final String processorBuckFile;

  private final Map<Set<Dependency>, Scope> dependencyToScopeMap = new ConcurrentHashMap<>();

  public AnnotationProcessorCache(
      Project project, OkBuckExtension extension, String processorBuckFile) {
    this.project = project;
    this.extension = extension;
    this.processorBuckFile = processorBuckFile;
  }

  /**
   * Get all the scopes for the specified configuration. Returns one configuration per dependency.
   * Will group auto value and its extensions together into one scope as well.
   *
   * @param project project on which the configuration is defined.
   * @param configurationString Configuration string which is used to query the deps.
   * @return A list of scopes generated by the configuration.
   */
  public List<Scope> getAnnotationProcessorScopes(Project project, String configurationString) {
    Configuration configuration = getConfiguration(project, configurationString);
    return configuration != null
        ? getAnnotationProcessorScopes(project, configuration)
        : ImmutableList.of();
  }

  /**
   * Get all the scopes for the specified configuration. Returns one configuration per dependency.
   * Will group auto value and its extensions together into one scope as well.
   *
   * @param project project on which the configuration is defined.
   * @param configuration Configuration which is used to query the deps.
   * @return A list of scopes generated by the configuration.
   */
  public List<Scope> getAnnotationProcessorScopes(Project project, Configuration configuration) {
    ImmutableList.Builder<Scope> scopesBuilder = ImmutableList.builder();
    ImmutableSet.Builder<Dependency> autoValueDependencyBuilder = ImmutableSet.builder();

    Map<Set<Dependency>, Scope> depToScope =
        createProcessorScopes(project, configuration.getAllDependencies(), false);

    for (Set<Dependency> dependencySet : depToScope.keySet()) {
      // Initialize with whether the dependency set contains any
      // auto value or auto-value.* dependency.
      @Var
      boolean autoValueDependency =
          dependencySet
              .stream()
              .anyMatch(
                  dependency ->
                      dependency.getGroup() != null
                          && dependency.getGroup().equals(AUTO_VALUE_GROUP)
                          && dependency.getName().startsWith(AUTO_VALUE_NAME));

      Scope scope = depToScope.get(dependencySet);
      if (scope != null) {
        // Whether the scope of the dependency set has any auto value extensions.
        if (scope.hasAutoValueExtensions()) {
          autoValueDependency = true;
        }

        // If an auto value dependency add it to the auto value dependency builder or
        // add the corresponding scope to the scope builder which will be returned.
        if (autoValueDependency) {
          autoValueDependencyBuilder.addAll(dependencySet);
        } else {
          scopesBuilder.add(scope);
        }
      }
    }

    // Compute new scope using the auto value dependencies and add it to the scope builder.
    ImmutableSet<Dependency> autoValueDependencies = autoValueDependencyBuilder.build();
    if (autoValueDependencies.size() > 0) {
      scopesBuilder.addAll(createProcessorScopes(project, autoValueDependencies, true).values());
    }

    return scopesBuilder.build();
  }

  /**
   * Checks if the configuration has any empty annotation processors.
   *
   * @param project project on which the configuration is defined.
   * @param configurationString ConfigurationString which is used to query the deps.
   * @return A boolean whether the configuration has any empty annotation processors.
   */
  @SuppressWarnings("BooleanMethodIsAlwaysInverted")
  public boolean hasEmptyAnnotationProcessors(Project project, String configurationString) {
    Configuration configuration = getConfiguration(project, configurationString);
    return hasEmptyAnnotationProcessors(project, configuration);
  }

  /**
   * Checks if the configuration has any empty annotation processors.
   *
   * @param project project on which the configuration is defined.
   * @param configuration Configuration which is used to query the deps.
   * @return A boolean whether the configuration has any empty annotation processors.
   */
  public boolean hasEmptyAnnotationProcessors(Project project, Configuration configuration) {
    Map<Set<Dependency>, Scope> depToScope =
        createProcessorScopes(project, configuration.getAllDependencies(), false);

    return depToScope
        .values()
        .stream()
        .anyMatch(scope -> scope.getAnnotationProcessors().isEmpty());
  }

  /** Write the buck file for the java_annotation_processor rules. */
  public void finalizeProcessors() {
    List<Rule> rules = JavaAnnotationProcessorRuleComposer.compose(dependencyToScopeMap.values());
    Multimap<String, String> loadStatements =
        LoadStatementsUtil.getLoadStatements(rules, extension);
    File buckFile = project.getRootProject().file(processorBuckFile);
    FileUtil.writeToBuckFile(loadStatements, rules, buckFile);
  }

  private Map<Set<Dependency>, Scope> createProcessorScopes(
      Project project, Set<Dependency> dependencies, boolean groupDependencies) {

    ImmutableMap.Builder<Set<Dependency>, Scope> currentBuilder = new ImmutableMap.Builder<>();

    // Creates a scope using a detached configuration and the given dependency set.
    Function<Set<Dependency>, Scope> computeScope =
        depSet -> {
          Dependency[] depArray = depSet.toArray(new Dependency[0]);
          Configuration detached = project.getConfigurations().detachedConfiguration(depArray);
          return Scope.builder(project).configuration(detached).build();
        };

    if (groupDependencies) {
      // Creates one scope for all the dependencies if not
      // already present and adds it to the current builder.
      ImmutableSet<Dependency> dependencySet = ImmutableSet.copyOf(dependencies);
      Scope scope = dependencyToScopeMap.computeIfAbsent(dependencySet, computeScope);
      currentBuilder.put(dependencySet, scope);

    } else {
      // Creates one scope per dependency if not already
      // found and adds it to the current builder.
      dependencies.forEach(
          dependency -> {
            ImmutableSet<Dependency> dependencySet = ImmutableSet.of(dependency);
            Scope scope = dependencyToScopeMap.computeIfAbsent(dependencySet, computeScope);
            currentBuilder.put(dependencySet, scope);
          });
    }
    return currentBuilder.build();
  }

  private static Configuration getConfiguration(Project project, String configurationString) {
    Configuration configuration = DependencyUtils.useful(configurationString, project);

    if (configuration == null) {
      throw new IllegalStateException(
          String.format(
              "No valid configuration found for '%s' in project '%s'",
              configurationString, project.getDisplayName()));
    }
    return configuration;
  }
}