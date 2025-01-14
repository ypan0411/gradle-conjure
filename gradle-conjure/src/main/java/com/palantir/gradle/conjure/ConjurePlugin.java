/*
 * (c) Copyright 2018 Palantir Technologies Inc. All rights reserved.
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

package com.palantir.gradle.conjure;

import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.palantir.gradle.conjure.api.ConjureExtension;
import com.palantir.gradle.conjure.api.ConjureProductDependenciesExtension;
import com.palantir.gradle.conjure.api.GeneratorOptions;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.UnknownDomainObjectException;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.file.FileCollection;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.plugins.BasePlugin;
import org.gradle.api.plugins.JavaLibraryPlugin;
import org.gradle.api.tasks.Delete;
import org.gradle.api.tasks.Exec;
import org.gradle.api.tasks.TaskOutputs;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.language.base.plugins.LifecycleBasePlugin;
import org.gradle.plugins.ide.eclipse.EclipsePlugin;
import org.gradle.plugins.ide.idea.IdeaPlugin;
import org.gradle.plugins.ide.idea.model.IdeaModule;

public final class ConjurePlugin implements Plugin<Project> {
    private static final Logger log = Logging.getLogger(ConjurePlugin.class);

    static final String TASK_GROUP = "Conjure";

    public static final String CONJURE_IR = ConjureBasePlugin.COMPILE_IR_TASK;

    // java project constants
    static final String JAVA_DIALOGUE_SUFFIX = "dialogue";
    static final String JAVA_OBJECTS_SUFFIX = "objects";
    static final String JAVA_JERSEY_SUFFIX = "jersey";
    static final String JAVA_RETROFIT_SUFFIX = "retrofit";
    static final String JAVA_UNDERTOW_SUFFIX = "undertow";
    static final ImmutableSet<String> JAVA_PROJECT_SUFFIXES = ImmutableSet.of(
            JAVA_DIALOGUE_SUFFIX, JAVA_OBJECTS_SUFFIX, JAVA_JERSEY_SUFFIX, JAVA_RETROFIT_SUFFIX, JAVA_UNDERTOW_SUFFIX);
    static final String JAVA_GENERATED_SOURCE_DIRNAME = "src/generated/java";
    static final String JAVA_GITIGNORE_CONTENTS = "/src/generated/java/\n";

    private static final ImmutableSet<String> FIRST_CLASS_GENERATOR_PROJECT_NAMES = ImmutableSet.<String>builder()
            .addAll(JAVA_PROJECT_SUFFIXES)
            .add("typescript")
            .add("python")
            .build();

    static final String CONJURE_JAVA_LIB_DEP = "com.palantir.conjure.java:conjure-lib";

    /** Configuration where custom generators should be added as dependencies. */
    static final String CONJURE_GENERATORS_CONFIGURATION_NAME = "conjureGenerators";

    static final String CONJURE_GENERATOR_DEP_PREFIX = "conjure-";
    /** Make the old Java8 @Generated annotation available even when compiling with Java9+. */
    static final String ANNOTATION_API = "jakarta.annotation:jakarta.annotation-api:1.3.5";

    /** Tells plugin to look for derived projects at same level as the api project rather than as child projects. */
    static final String USE_FLAT_PROJECT_STRUCTURE_PROPERTY = "com.palantir.conjure.use_flat_project_structure";

    /** Tells plugin the names of generic generator derived projects when in flat mode. */
    static final String GENERIC_GENERATOR_LANGUAGE_NAMES_PROPERTY = "com.palantir.conjure.generator_language_names";

    @Override
    public void apply(Project project) {
        project.getPlugins().apply(BasePlugin.class);
        project.getPluginManager().apply(ConjureBasePlugin.class);

        TaskProvider<?> compileIrTask = project.getTasks().named(ConjureBasePlugin.COMPILE_IR_TASK);
        TaskProvider<GenerateConjureServiceDependenciesTask> serviceDependencyTask = project.getTasks()
                .named(ConjureBasePlugin.SERVICE_DEPENDENCIES_TASK, GenerateConjureServiceDependenciesTask.class);

        ConjureExtension conjureExtension =
                project.getExtensions().create(ConjureExtension.EXTENSION_NAME, ConjureExtension.class);
        Configuration conjureGeneratorsConfiguration =
                project.getConfigurations().maybeCreate(CONJURE_GENERATORS_CONFIGURATION_NAME);

        // Set up conjure compile task
        TaskProvider<DefaultTask> compileConjure = project.getTasks()
                .register("compileConjure", DefaultTask.class, task -> {
                    task.setDescription("Generates code for your API definitions in src/main/conjure/**/*.yml");
                    task.setGroup(TASK_GROUP);
                });
        applyDependencyForIdeTasks(project, compileConjure);

        setupConjureJavaProjects(
                project, immutableOptionsSupplier(conjureExtension::getJava), compileConjure, compileIrTask);
        setupConjurePythonProject(
                project, immutableOptionsSupplier(conjureExtension::getPython), compileConjure, compileIrTask);
        setupConjureTypescriptProject(
                project,
                immutableOptionsSupplier(conjureExtension::getTypescript),
                compileConjure,
                compileIrTask,
                serviceDependencyTask);
        setupGenericConjureProjects(
                project,
                conjureExtension::getGenericOptions,
                compileConjure,
                compileIrTask,
                conjureGeneratorsConfiguration);
    }

    private void setupConjureJavaProjects(
            Project project,
            Supplier<GeneratorOptions> optionsSupplier,
            TaskProvider<?> compileConjure,
            TaskProvider<?> compileIrTask) {

        if (JAVA_PROJECT_SUFFIXES.stream()
                .noneMatch(suffix -> findDerivedProject(project, getDerivedProjectName(project, suffix)) != null)) {
            return;
        }

        ConjureProductDependenciesExtension productDependencyExt =
                project.getExtensions().getByType(ConjureProductDependenciesExtension.class);

        TaskProvider<ExtractExecutableTask> extractJavaTask = ExtractConjurePlugin.applyConjureJava(project);

        Map<String, Consumer<Project>> configs = ImmutableMap.<String, Consumer<Project>>builder()
                .put(JAVA_OBJECTS_SUFFIX, (Consumer<Project>) ConjurePlugin::setupObjectsProject)
                .put(JAVA_DIALOGUE_SUFFIX, (Consumer<Project>) ConjurePlugin::setupDialogueProject)
                .put(JAVA_RETROFIT_SUFFIX, (Consumer<Project>) ConjurePlugin::setupRetrofitProject)
                .put(JAVA_JERSEY_SUFFIX, (Consumer<Project>) ConjurePlugin::setupJerseyProject)
                .put(JAVA_UNDERTOW_SUFFIX, (Consumer<Project>) ConjurePlugin::setupUndertowProject)
                .build();

        // Make sure project names align
        Sets.SetView<String> difference = Sets.difference(configs.keySet(), JAVA_PROJECT_SUFFIXES);
        if (!difference.isEmpty()) {
            throw new GradleException(
                    "Known java project types do not match with projects to configure.  Did you add a new project type"
                            + " and not add it to JAVA_PROJECT_SUFFIXES? Diffs: "
                            + difference);
        }

        configs.forEach((suffix, config) -> setupDerivedJavaProject(
                suffix,
                project,
                optionsSupplier,
                compileConjure,
                compileIrTask,
                productDependencyExt,
                extractJavaTask,
                config));
    }

    private static Project setupDerivedJavaProject(
            String projectSuffix,
            Project parentProject,
            Supplier<GeneratorOptions> optionsSupplier,
            TaskProvider<?> compileConjure,
            TaskProvider<?> compileIrTask,
            ConjureProductDependenciesExtension productDependencyExt,
            TaskProvider<ExtractExecutableTask> extractJavaTask,
            Consumer<Project> extraConfig) {
        String projectName = getDerivedProjectName(parentProject, projectSuffix);
        if (!derivedProjectExists(parentProject, projectName)) {
            return null;
        }

        String objectsProjectName = getDerivedProjectName(parentProject, JAVA_OBJECTS_SUFFIX);
        boolean isNotObjectsProject = !projectName.equals(objectsProjectName);
        if (isNotObjectsProject && !derivedProjectExists(parentProject, objectsProjectName)) {
            throw new IllegalStateException(
                    String.format("Cannot enable '%s' without '%s'", projectName, objectsProjectName));
        }

        String upperSuffix = getUppercaseSuffix(projectSuffix);
        return parentProject.project(derivedProjectPath(parentProject, projectName), subproj -> {
            subproj.getPluginManager().apply(JavaLibraryPlugin.class);
            ignoreFromCheckUnusedDependencies(subproj);
            addGeneratedToMainSourceSet(subproj);
            TaskProvider<ConjureGeneratorTask> conjureGeneratorTask = parentProject
                    .getTasks()
                    .register("compileConjure" + upperSuffix, ConjureGeneratorTask.class, task -> {
                        task.setDescription(
                                String.format("Generates %s interfaces from your Conjure definitions.", upperSuffix));
                        task.setGroup(TASK_GROUP);
                        task.getExecutablePath().set(extractJavaTask.flatMap(ExtractExecutableTask::getExecutable));
                        task.setOptions(() -> optionsSupplier.get().addFlag(projectSuffix));
                        task.getOutputDirectory().set(subproj.file(JAVA_GENERATED_SOURCE_DIRNAME));
                        task.setSource(compileIrTask);

                        task.dependsOn(createWriteGitignoreTask(
                                subproj,
                                "gitignoreConjure" + upperSuffix,
                                subproj.getProjectDir(),
                                JAVA_GITIGNORE_CONTENTS));
                        task.dependsOn(extractJavaTask);
                    });
            subproj.getTasks().named("compileJava").configure(t -> t.dependsOn(conjureGeneratorTask));
            applyDependencyForIdeTasks(subproj, conjureGeneratorTask);
            compileConjure.configure(t -> t.dependsOn(conjureGeneratorTask));

            registerClean(parentProject, conjureGeneratorTask);
            if (isNotObjectsProject) {
                subproj.getDependencies().add("api", findDerivedProject(parentProject, objectsProjectName));
            }
            if (shouldConfigureJavaServices(subproj)) {
                ConjureJavaServiceDependencies.configureJavaServiceDependencies(subproj, productDependencyExt);
            }
            if (extraConfig != null) {
                extraConfig.accept(subproj);
            }
        });
    }

    static void registerClean(Project project, TaskProvider<? extends Task> creatorTask) {
        String cleanTaskName = "clean" + getUppercaseSuffix(creatorTask.getName());
        TaskProvider<Task> cleanTask = project.getTasks().named(LifecycleBasePlugin.CLEAN_TASK_NAME);
        // This replicates what the built in gradle CleanRule does, but using task-avoidance APIs
        TaskProvider<Delete> cleanerTask = project.getTasks().register(cleanTaskName, Delete.class, t -> {
            t.delete(creatorTask.map(Task::getOutputs).map(TaskOutputs::getFiles));
        });
        cleanTask.configure(t -> {
            t.dependsOn(cleanerTask);
        });
    }

    private static String getUppercaseSuffix(String suffix) {
        return suffix.substring(0, 1).toUpperCase(Locale.ROOT) + suffix.substring(1);
    }

    private static String getDerivedProjectName(Project parent, String suffix) {
        return parent.getName() + "-" + suffix;
    }

    /**
     * Objects projects and server-side only projects (i.e. undertow) should not have the recommended dependencies
     * configured.
     */
    private static boolean shouldConfigureJavaServices(Project project) {
        String projectName = project.getName();
        return !(projectName.endsWith(JAVA_OBJECTS_SUFFIX) || projectName.endsWith(JAVA_UNDERTOW_SUFFIX));
    }

    private static void setupObjectsProject(Project project) {
        project.getDependencies().add("api", "com.palantir.conjure.java:conjure-lib");
    }

    private static void setupDialogueProject(Project project) {
        project.getDependencies().add("api", "com.palantir.dialogue:dialogue-target");
    }

    private static void setupRetrofitProject(Project project) {
        project.getDependencies().add("api", "com.google.guava:guava");
        project.getDependencies().add("api", "com.squareup.retrofit2:retrofit");
        project.getDependencies().add("compileOnly", ANNOTATION_API);
    }

    private static void setupJerseyProject(Project project) {
        project.getDependencies().add("api", "jakarta.ws.rs:jakarta.ws.rs-api");
        project.getDependencies().add("compileOnly", ANNOTATION_API);
    }

    private static void setupUndertowProject(Project project) {
        project.getDependencies().add("api", "com.palantir.conjure.java:conjure-undertow-lib");
    }

    @SuppressWarnings({"unchecked", "RawTypes"})
    private static void ignoreFromCheckUnusedDependencies(Project proj) {
        proj.getPlugins().withId("com.palantir.baseline-exact-dependencies", plugin -> {
            Class<? extends Task> checkUnusedDependenciesTask;
            try {
                ClassLoader baselineClassloader = plugin.getClass().getClassLoader();
                checkUnusedDependenciesTask = (Class<? extends Task>)
                        baselineClassloader.loadClass("com.palantir.baseline.tasks.CheckUnusedDependenciesTask");
            } catch (ClassNotFoundException e) {
                log.warn("Failed to ignore conjure-lib from baseline's checkUnusedDependencies", e);
                return;
            }

            proj.getTasks().withType(checkUnusedDependenciesTask, task -> {
                try {
                    Method ignoreMethod = task.getClass().getMethod("ignore", String.class, String.class);
                    List<String> conjureJavaLibComponents = Splitter.on(':').splitToList(CONJURE_JAVA_LIB_DEP);
                    ignoreMethod.invoke(task, conjureJavaLibComponents.get(0), conjureJavaLibComponents.get(1));
                    // also ignore guava since retrofit adds it...
                    ignoreMethod.invoke(task, "com.google.guava", "guava");
                } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
                    log.warn("Failed to ignore conjure-lib from baseline's checkUnusedDependencies", e);
                }
            });
        });
    }

    private static void setupConjureTypescriptProject(
            Project project,
            Supplier<GeneratorOptions> options,
            TaskProvider<?> compileConjure,
            TaskProvider<?> compileIrTask,
            TaskProvider<GenerateConjureServiceDependenciesTask> productDependencyTask) {
        String typescriptProjectName = project.getName() + "-typescript";
        if (derivedProjectExists(project, typescriptProjectName)) {
            project.project(derivedProjectPath(project, typescriptProjectName), subproj -> {
                applyDependencyForIdeTasks(subproj, compileConjure);
                File srcDirectory = subproj.file("src");
                TaskProvider<ExtractExecutableTask> extractConjureTypeScriptTask =
                        ExtractConjurePlugin.applyConjureTypeScript(project);
                TaskProvider<CompileConjureTypeScriptTask> compileConjureTypeScript = project.getTasks()
                        .register("compileConjureTypeScript", CompileConjureTypeScriptTask.class, task -> {
                            task.setDescription("Generates TypeScript files and a package.json from your "
                                    + "Conjure definitions.");
                            task.setGroup(TASK_GROUP);
                            task.setSource(compileIrTask);
                            task.getExecutablePath()
                                    .set(extractConjureTypeScriptTask.flatMap(ExtractExecutableTask::getExecutable));
                            task.getProductDependencyFile()
                                    .set(productDependencyTask.flatMap(
                                            GenerateConjureServiceDependenciesTask::getOutputFile));
                            task.getOutputDirectory().set(srcDirectory);
                            task.setOptions(options);
                            task.dependsOn(createWriteGitignoreTask(
                                    subproj, "gitignoreConjureTypeScript", subproj.getProjectDir(), "/src/\n"));
                            task.dependsOn(extractConjureTypeScriptTask);
                            task.dependsOn(productDependencyTask);
                        });
                compileConjure.configure(t -> t.dependsOn(compileConjureTypeScript));
                registerClean(project, compileConjureTypeScript);

                String npmCommand = OsUtils.NPM_COMMAND_NAME;
                TaskProvider<Exec> installTypeScriptDependencies = project.getTasks()
                        .register("installTypeScriptDependencies", Exec.class, task -> {
                            task.commandLine(npmCommand, "install", "--no-package-lock", "--no-production");
                            task.workingDir(srcDirectory);
                            task.dependsOn(compileConjureTypeScript);
                            task.getInputs().file(new File(srcDirectory, "package.json"));
                            task.getOutputs().dir(new File(srcDirectory, "node_modules"));
                        });
                TaskProvider<Exec> compileTypeScript = project.getTasks()
                        .register("compileTypeScript", Exec.class, task -> {
                            task.setDescription(
                                    "Runs `npm tsc` to compile generated TypeScript files into JavaScript files.");
                            task.setGroup(TASK_GROUP);
                            task.commandLine(npmCommand, "run-script", "build");
                            task.workingDir(srcDirectory);
                            task.dependsOn(installTypeScriptDependencies);
                            task.getOutputs().dir(srcDirectory);
                        });
                TaskProvider<Exec> publishTypeScript = project.getTasks()
                        .register("publishTypeScript", Exec.class, task -> {
                            task.setDescription("Runs `npm publish` to publish a TypeScript package "
                                    + "generated from your Conjure definitions.");
                            task.setGroup(TASK_GROUP);
                            task.commandLine(npmCommand, "publish");
                            task.workingDir(srcDirectory);
                            task.dependsOn(compileConjureTypeScript);
                            task.dependsOn(compileTypeScript);
                        });
                linkPublish(subproj, publishTypeScript);
            });
        }
    }

    private static void linkPublish(Project project, TaskProvider<?> depTask) {
        // this is the cleanest and most common way to link to the publish task.
        project.getPluginManager().withPlugin("maven-publish", _packagingPlugin -> {
            project.getTasks().named("publish").configure(t -> t.dependsOn(depTask));
        });
        // In case the regular plugin is not applied, register a sub-publish task
        project.afterEvaluate(p -> {
            if (!p.getPluginManager().hasPlugin("maven-publish")) {
                TaskProvider<Task> publishProvider;
                try {
                    publishProvider = p.getTasks().named("publish");
                } catch (UnknownDomainObjectException e) {
                    p.getLogger().debug("Manually creating publish task", e);
                    publishProvider = p.getTasks().register("publish");
                }
                publishProvider.configure(t -> t.dependsOn(depTask));
            }
        });
    }

    private static void setupConjurePythonProject(
            Project project,
            Supplier<GeneratorOptions> options,
            TaskProvider<?> compileConjure,
            TaskProvider<?> compileIrTask) {
        String pythonProjectName = project.getName() + "-python";
        if (derivedProjectExists(project, pythonProjectName)) {
            project.project(derivedProjectPath(project, pythonProjectName), subproj -> {
                applyDependencyForIdeTasks(subproj, compileConjure);
                File buildDir = new File(project.getBuildDir(), "python");
                File distDir = new File(buildDir, "dist");
                TaskProvider<ExtractExecutableTask> extractConjurePythonTask =
                        ExtractConjurePlugin.applyConjurePython(project);
                TaskProvider<CompileConjurePythonTask> compileConjurePython = project.getTasks()
                        .register("compileConjurePython", CompileConjurePythonTask.class, task -> {
                            task.setDescription("Generates Python files from your Conjure definitions.");
                            task.setGroup(TASK_GROUP);
                            task.setSource(compileIrTask);
                            task.getExecutablePath()
                                    .set(extractConjurePythonTask.flatMap(ExtractExecutableTask::getExecutable));
                            task.getOutputDirectory().set(subproj.file("python"));
                            task.setOptions(options);
                            task.dependsOn(createWriteGitignoreTask(
                                    subproj, "gitignoreConjurePython", subproj.getProjectDir(), "/python/\n"));
                            task.dependsOn(extractConjurePythonTask);
                        });
                compileConjure.configure(t -> t.dependsOn(compileConjurePython));
                project.getTasks().register("buildWheel", Exec.class, task -> {
                    task.setDescription("Runs `python setup.py sdist bdist_wheel --universal` to build a python wheel "
                            + "generated from your Conjure definitions.");
                    task.setGroup(TASK_GROUP);
                    task.commandLine(
                            "python",
                            "setup.py",
                            "build",
                            "--build-base",
                            buildDir,
                            "egg_info",
                            "--egg-base",
                            buildDir,
                            "sdist",
                            "--dist-dir",
                            distDir,
                            "bdist_wheel",
                            "--universal",
                            "--dist-dir",
                            distDir);
                    task.workingDir(subproj.file("python"));
                    task.dependsOn(compileConjurePython);
                });
                registerClean(project, compileConjurePython);
            });
        }
    }

    private static void setupGenericConjureProjects(
            Project project,
            Function<String, GeneratorOptions> getGenericOptions,
            TaskProvider<?> compileConjure,
            TaskProvider<?> compileIrTask,
            Configuration conjureGeneratorsConfiguration) {
        Map<String, Project> genericSubProjects = findGenericDerivedProjects(project);
        if (genericSubProjects.isEmpty()) {
            return;
        }

        // Validating that each subproject has a corresponding generator.
        // We do this in afterEvaluate to ensure the configuration is populated.
        project.afterEvaluate(p -> {
            Map<String, Dependency> generators = conjureGeneratorsConfiguration.getAllDependencies().stream()
                    .collect(Collectors.toMap(
                            dependency -> {
                                Preconditions.checkState(
                                        dependency.getName().startsWith(CONJURE_GENERATOR_DEP_PREFIX),
                                        "Generators should start with '%s' according to conjure RFC 002, "
                                                + "but found name: '%s' (%s)",
                                        CONJURE_GENERATOR_DEP_PREFIX,
                                        dependency.getName(),
                                        dependency);
                                return dependency.getName().substring(CONJURE_GENERATOR_DEP_PREFIX.length());
                            },
                            Function.identity()));

            genericSubProjects.forEach((subprojectName, subproject) -> {
                String conjureLanguage = extractSubprojectLanguage(p.getName(), subprojectName);
                if (!FIRST_CLASS_GENERATOR_PROJECT_NAMES.contains(conjureLanguage)
                        && !generators.containsKey(conjureLanguage)) {
                    throw new RuntimeException(String.format(
                            "Discovered subproject %s without corresponding generator dependency with name '%s'",
                            subproject.getPath(), ConjurePlugin.CONJURE_GENERATOR_DEP_PREFIX + conjureLanguage));
                }
            });
        });

        genericSubProjects.forEach((subprojectName, subproject) -> {
            String conjureLanguage = extractSubprojectLanguage(project.getName(), subprojectName);

            // We create a lazy filtered FileCollection to avoid using afterEvaluate.
            FileCollection matchingGeneratorDeps = conjureGeneratorsConfiguration.fileCollection(
                    dep -> dep.getName().equals(CONJURE_GENERATOR_DEP_PREFIX + conjureLanguage));

            TaskProvider<ExtractExecutableTask> extractConjureGeneratorTask = ExtractExecutableTask.createExtractTask(
                    project,
                    "extractConjure" + StringUtils.capitalize(conjureLanguage),
                    matchingGeneratorDeps,
                    new File(subproject.getBuildDir(), "generator"),
                    String.format("conjure-%s", conjureLanguage));

            String taskName = "compileConjure" + StringUtils.capitalize(conjureLanguage);
            TaskProvider<ConjureGeneratorTask> conjureLocalGenerateTask = project.getTasks()
                    .register(taskName, ConjureGeneratorTask.class, task -> {
                        task.setDescription(
                                String.format("Generates %s files from your Conjure definition.", conjureLanguage));
                        task.setGroup(ConjurePlugin.TASK_GROUP);
                        task.setSource(compileIrTask);
                        task.getExecutablePath()
                                .set(extractConjureGeneratorTask.flatMap(ExtractExecutableTask::getExecutable));
                        task.setOptions(() -> getGenericOptions.apply(conjureLanguage));
                        task.getOutputDirectory().set(subproject.file("src"));
                        task.dependsOn(extractConjureGeneratorTask);
                    });
            compileConjure.configure(t -> t.dependsOn(conjureLocalGenerateTask));
        });
    }

    /**
     * Locates projects either as child projects or as peer projects whose names match the patterns given by
     * the GENERIC_GENERATOR_LANGUAGE_NAMES_PROPERTY property.
     */
    private static Map<String, Project> findGenericDerivedProjects(Project project) {
        String projectName = project.getName();

        boolean useFlatProjectStructure = project.hasProperty(USE_FLAT_PROJECT_STRUCTURE_PROPERTY);
        if (!useFlatProjectStructure) {
            return Maps.filterKeys(project.getChildProjects(), childProjectName -> {
                return childProjectName.startsWith(projectName)
                        && !FIRST_CLASS_GENERATOR_PROJECT_NAMES.contains(
                                extractSubprojectLanguage(projectName, childProjectName));
            });
        } else if (project.hasProperty(GENERIC_GENERATOR_LANGUAGE_NAMES_PROPERTY)) {
            String names = (String) project.getProperties().get(GENERIC_GENERATOR_LANGUAGE_NAMES_PROPERTY);
            List<String> genericLanguages = Arrays.asList(names.split(",\\s*"));
            return genericLanguages.stream()
                    .map(language -> projectName + "-" + language)
                    .filter(derivedProjectName -> derivedProjectExists(project, derivedProjectName))
                    .map(derivedProjectName -> findDerivedProject(project, derivedProjectName))
                    .collect(Collectors.toMap(Project::getName, derivedProject -> derivedProject));
        }

        return Collections.emptyMap();
    }

    // TODO(fwindheuser): Replace 'JavaPluginConvention'  with 'JavaPluginExtension' after dropping Gradle 6 support.
    @SuppressWarnings("deprecation")
    static void addGeneratedToMainSourceSet(Project subproj) {
        org.gradle.api.plugins.JavaPluginConvention javaPlugin =
                subproj.getConvention().findPlugin(org.gradle.api.plugins.JavaPluginConvention.class);
        javaPlugin.getSourceSets().getByName("main").getJava().srcDir(subproj.files(JAVA_GENERATED_SOURCE_DIRNAME));
    }

    static void applyDependencyForIdeTasks(Project project, TaskProvider<?> compileConjure) {
        project.getPlugins().withType(IdeaPlugin.class, plugin -> {
            // root project does not have the ideaModule task.  There is unfortunately no
            // safe way to check for existence with the task avoidance APIs
            try {
                project.getTasks().named("ideaModule").configure(t -> t.dependsOn(compileConjure));
            } catch (UnknownDomainObjectException e) {
                project.getLogger().debug("Project does not have ideaModule task.", e);
            }

            IdeaModule module = plugin.getModel().getModule();

            // module.getSourceDirs / getGeneratedSourceDirs could be an immutable set, so defensively copy
            module.setSourceDirs(
                    mutableSetWithExtraEntry(module.getSourceDirs(), project.file(JAVA_GENERATED_SOURCE_DIRNAME)));

            module.setGeneratedSourceDirs(mutableSetWithExtraEntry(
                    module.getGeneratedSourceDirs(), project.file(JAVA_GENERATED_SOURCE_DIRNAME)));
        });
        project.getPlugins().withType(EclipsePlugin.class, _plugin -> {
            Task task = project.getTasks().findByName("eclipseClasspath");
            if (task != null) {
                task.dependsOn(compileConjure);
            }
        });
    }

    private static <T> Set<T> mutableSetWithExtraEntry(Set<T> set, T extraItem) {
        Set<T> newSet = new LinkedHashSet<>(set);
        newSet.add(extraItem);
        return newSet;
    }

    static TaskProvider<WriteGitignoreTask> createWriteGitignoreTask(
            Project project, String taskName, File outputDir, String contents) {
        TaskProvider<WriteGitignoreTask> writeGitignoreTask = project.getTasks()
                .register(taskName, WriteGitignoreTask.class, task -> {
                    task.setOutputDirectory(outputDir);
                    task.setContents(contents);
                });
        return writeGitignoreTask;
    }

    private static Supplier<GeneratorOptions> immutableOptionsSupplier(Supplier<GeneratorOptions> supplier) {
        return () -> new GeneratorOptions(supplier.get());
    }

    private static String extractSubprojectLanguage(String projectName, String subprojectName) {
        return subprojectName.substring(projectName.length() + 1);
    }

    private static boolean derivedProjectExists(Project project, String projectName) {
        return findDerivedProject(project, projectName) != null;
    }

    private static String derivedProjectPath(Project project, String projectName) {
        Project derivedProject = findDerivedProject(project, projectName);
        if (derivedProject == null) {
            throw new IllegalStateException(
                    String.format("Cannot get path for '%s' because it does not exist", projectName));
        }
        return derivedProject.getPath();
    }

    private static Project findDerivedProject(Project project, String projectName) {
        boolean useFlatProjectStructure = project.hasProperty(USE_FLAT_PROJECT_STRUCTURE_PROPERTY);
        if (!useFlatProjectStructure) {
            return project.findProject(projectName);
        } else {
            return project.getRootProject().findProject(projectName);
        }
    }
}
