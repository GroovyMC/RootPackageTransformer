package org.groovymc.rootpackagetransformer.plugin;

import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.type.ArtifactTypeDefinition;
import org.gradle.api.attributes.Attribute;
import org.gradle.api.attributes.LibraryElements;
import org.gradle.api.component.AdhocComponentWithVariants;
import org.gradle.api.file.CopySpec;
import org.gradle.api.file.FileCollection;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.provider.Property;
import org.gradle.api.specs.Spec;
import org.gradle.api.tasks.Copy;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Jar;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.Set;

public abstract class RootPackageTransformerPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        project.getPluginManager().apply("java");
        project.getExtensions().create("rootPackageTransformer", Extension.class, project);
    }

    public static abstract class Extension {
        private final Project project;

        @Inject
        public Extension(Project project) {
            this.project = project;
        }

        public void forSourceSet(SourceSet sourceSet, String newBaseCapability) {
            forSourceSet(sourceSet, newBaseCapability, settings -> {});
        }

        public void forSourceSet(SourceSet sourceSet, String newBaseCapability, Action<TransformSettings> action) {
            TransformSettings settings = project.getObjects().newInstance(TransformSettings.class);
            action.execute(settings);

            var transformList = project.getTasks().register(sourceSet.getTaskName("rootPackageTransformList", ""), TransformListTask.class, task -> {
                var dirs = sourceSet.getOutput().getClassesDirs();
                task.dependsOn(dirs.getBuildDependencies());
                dirs.forEach(f -> task.getInputFiles().from(project.fileTree(f)));
            });

            var transform = project.getTasks().register(sourceSet.getTaskName("rootPackageTransform", ""), TransformTask.class, task -> {
                var dirs = sourceSet.getOutput().getClassesDirs();
                task.dependsOn(dirs.getBuildDependencies());
                dirs.forEach(f -> task.getInputFiles().from(project.fileTree(f)));
                task.getListFile().set(transformList.get().getListFile());
                task.dependsOn(transformList.get());
            });

            ManifestLocation jarManifest = manifestLocation(sourceSet, "jar");

            project.getTasks().named(sourceSet.getTaskName("jar", ""), Jar.class, task -> {
                task.from(transformList.get().getListFile(), spec -> {
                    spec.into("META-INF");
                });
            });

            var rootPackageJar = project.getTasks().register(sourceSet.getTaskName("rootPackageJar", ""), Jar.class, task -> {
                task.dependsOn(transform.get());
                task.from(transform.get().getOutputDirectory());
                task.getArchiveClassifier().set("rootpackage-"+sourceSet.getName());
                task.from(sourceSet.getOutput().getResourcesDir());

                task.from(transformList.get().getListFile(), spec -> {
                    spec.into("META-INF");
                });

                task.manifest(m -> m.from(jarManifest.manifestDir().get().file("META-INF/MANIFEST.MF")));
                task.dependsOn(jarManifest.originalManifest());
            });

            AdhocComponentWithVariants javaComponent = (AdhocComponentWithVariants) project.getComponents().getByName("java");

            setupRootPackageElements(sourceSet, newBaseCapability, rootPackageJar, transform, javaComponent);

            var assemble = project.getTasks().named("assemble");

            if (settings.getTransformSources().get()) {
                var sourcesJarManifest = manifestLocation(sourceSet, "sourcesJar");

                var transformSources = project.getTasks().register(sourceSet.getTaskName("rootPackageTransformSources", ""), TransformSourcesTask.class, task -> {
                    task.getListFile().set(transformList.get().getListFile());
                    task.getTransformedExtensions().set(settings.getTransformedSourceExtensions());
                    FileCollection dirs = sourceSet.getAllSource();
                    task.dependsOn(dirs.getBuildDependencies());
                    task.getCopyConfiguration().set(new SpecConfiguration(dirs));
                });

                var rootPackageSourcesJar = project.getTasks().register(sourceSet.getTaskName("rootPackageSourcesJar", ""), Jar.class, task -> {
                    task.dependsOn(transformSources.get());
                    task.from(transformSources.get().getDestinationDirectory());
                    task.getArchiveClassifier().set("rootpackage-"+sourceSet.getName()+"-sources");

                    task.from(transformList.get().getListFile(), spec -> {
                        spec.into("META-INF");
                    });

                    task.manifest(m -> m.from(sourcesJarManifest.manifestDir().get().file("META-INF/MANIFEST.MF")));
                    task.dependsOn(sourcesJarManifest.originalManifest());
                });

                var rootPackageSourcesElements = setupRootPackageSourcesElements(sourceSet, newBaseCapability, rootPackageSourcesJar);
                javaComponent.addVariantsFromConfiguration(rootPackageSourcesElements, v -> {});

                assemble.configure(task -> {
                    task.dependsOn(rootPackageSourcesJar);
                });
            }

            assemble.configure(task -> {
                task.dependsOn(rootPackageJar);
            });
        }

        // Can't be a lambda or local class because gradle serialization doesn't like it
        // Can't be a record because we're running with gradle versions before those're supported
        @SuppressWarnings("ClassCanBeRecord")
        private static class SpecConfiguration implements Action<CopySpec> {
            private final FileCollection source;

            private SpecConfiguration(FileCollection source) {
                this.source = source;
            }

            @Override
            public void execute(CopySpec copySpec) {
                copySpec.from(source);
            }
        }

        private ManifestLocation manifestLocation(SourceSet sourceSet, String taskName) {
            var originalManifestName = sourceSet.getTaskName(taskName+"RootPackageOriginalManifest", "");
            var manifestDir = project.getLayout().getBuildDirectory().dir("extracted/"+originalManifestName);

            var originalManifest = project.getTasks().register(originalManifestName, Copy.class, task -> {
                var jar = (Jar) project.getTasks().getByName(sourceSet.getTaskName(taskName, ""));
                task.dependsOn(jar);
                task.from(project.zipTree(jar.getArchiveFile()));
                task.include("META-INF/MANIFEST.MF");
                task.into(manifestDir);
            });
            return new ManifestLocation(manifestDir, originalManifest);
        }

        private record ManifestLocation(org.gradle.api.provider.Provider<org.gradle.api.file.Directory> manifestDir, TaskProvider<Copy> originalManifest) { }

        private void setupRootPackageElements(SourceSet sourceSet, String newBaseCapability, TaskProvider<Jar> rootPackageJar, TaskProvider<TransformTask> transform, AdhocComponentWithVariants component) {
            var rootPackageRuntimeElements = project.getConfigurations().maybeCreate(sourceSet.getTaskName("rootPackageRuntimeElements", ""));
            var runtimeElements = project.getConfigurations().getByName(sourceSet.getTaskName(JavaPlugin.RUNTIME_ELEMENTS_CONFIGURATION_NAME, ""));

            var rootPackageApiElements = project.getConfigurations().maybeCreate(sourceSet.getTaskName("rootPackageApiElements", ""));
            var apiElements = project.getConfigurations().getByName(sourceSet.getTaskName(JavaPlugin.API_ELEMENTS_CONFIGURATION_NAME, ""));

            setupElementsCopyOf(sourceSet, newBaseCapability, rootPackageJar, transform, rootPackageRuntimeElements, runtimeElements);
            setupElementsCopyOf(sourceSet, newBaseCapability, rootPackageJar, transform, rootPackageApiElements, apiElements);

            component.addVariantsFromConfiguration(rootPackageRuntimeElements, v -> {
                v.mapToMavenScope("runtime");
                v.mapToOptional();
                if (!v.getConfigurationVariant().getName().equals(rootPackageRuntimeElements.getName())) {
                    v.skip();
                }
            });

            component.addVariantsFromConfiguration(rootPackageApiElements, v -> {
                v.mapToMavenScope("compile");
                v.mapToOptional();
                if (!v.getConfigurationVariant().getName().equals(rootPackageApiElements.getName())) {
                    v.skip();
                }
            });
        }

        private void setupElementsCopyOf(SourceSet sourceSet, String newBaseCapability, TaskProvider<Jar> rootPackageJar, TaskProvider<TransformTask> transform, Configuration rootPackageElements, Configuration originalElements) {
            rootPackageElements.setCanBeConsumed(true);
            rootPackageElements.setCanBeResolved(false);
            rootPackageElements.getDependencies().addAllLater(project.provider(originalElements::getAllDependencies));
            if (!sourceSet.getName().equals("main")) {
                rootPackageElements.getOutgoing().capability(project.getGroup()+":"+project.getName()+"-"+sourceSet.getName()+":"+project.getVersion());
            }
            copyAttributes(originalElements, rootPackageElements);
            originalElements.getOutgoing().capability(newBaseCapability);

            rootPackageElements.getOutgoing().getVariants().create("classes", variant -> {
                variant.artifact(transform.get().getOutputDirectory(), artifact -> {
                    artifact.setType(ArtifactTypeDefinition.JVM_CLASS_DIRECTORY);
                    artifact.builtBy(transform.get());
                });
                variant.attributes(attrs -> {
                    attrs.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, project.getObjects().named(LibraryElements.class, LibraryElements.CLASSES));
                });
            });

            rootPackageElements.getOutgoing().getVariants().create("resources", variant -> {
                variant.artifact(sourceSet.getOutput().getResourcesDir(), artifact -> {
                    artifact.setType(ArtifactTypeDefinition.JVM_RESOURCES_DIRECTORY);
                    artifact.builtBy(sourceSet.getProcessResourcesTaskName());
                });
                variant.attributes(attrs -> {
                    attrs.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, project.getObjects().named(LibraryElements.class, LibraryElements.RESOURCES));
                });
            });

            project.artifacts(artifactHandler ->
                artifactHandler.add(rootPackageElements.getName(), rootPackageJar.get(), spec -> {
                    spec.setClassifier(sourceSetClassifier(sourceSet, ""));
                })
            );

            prefixArtifacts(originalElements);
        }

        private void prefixArtifacts(Configuration elements) {
            var artifacts = new ArrayList<>(elements.getOutgoing().getArtifacts());
            elements.getOutgoing().getArtifacts().clear();
            project.artifacts(ah -> {
                for (var artifact : artifacts) {
                    ah.add(elements.getName(), artifact.getFile(), artifactConfig -> {
                        artifactConfig.builtBy(artifact.getBuildDependencies());
                        artifactConfig.setType(artifact.getType());
                        artifactConfig.setExtension(artifact.getExtension());
                        artifactConfig.setClassifier((artifact.getClassifier() == null || artifact.getClassifier().isEmpty()) ? "jpms" : "jpms-" + artifact.getClassifier());
                    });
                }
            });
        }

        private Configuration setupRootPackageSourcesElements(SourceSet sourceSet, String newBaseCapability, TaskProvider<Jar> rootPackageSourcesJar) {
            var rootPackageSourcesElements = project.getConfigurations().maybeCreate(sourceSet.getTaskName("rootPackageSourcesElements", ""));
            rootPackageSourcesElements.setCanBeConsumed(true);
            rootPackageSourcesElements.setCanBeResolved(false);
            var sourcesElements = project.getConfigurations().getByName(sourceSet.getTaskName(JavaPlugin.SOURCES_ELEMENTS_CONFIGURATION_NAME, ""));
            rootPackageSourcesElements.getDependencies().addAllLater(project.provider(sourcesElements::getDependencies));
            copyAttributes(sourcesElements, rootPackageSourcesElements);
            sourcesElements.getOutgoing().capability(newBaseCapability);
            project.artifacts(artifactHandler ->
                artifactHandler.add(rootPackageSourcesElements.getName(), rootPackageSourcesJar.get(), spec -> {
                    spec.setClassifier(sourceSetClassifier(sourceSet, "sources"));
                })
            );

            prefixArtifacts(sourcesElements);

            return rootPackageSourcesElements;
        }

        private static String sourceSetClassifier(SourceSet sourceSet, String classifier) {
            if (sourceSet.getName().equals("main")) {
                return classifier;
            } else if (classifier.isEmpty()) {
                return sourceSet.getName();
            } else {
                return sourceSet.getName() + "-" + classifier;
            }
        }

        public static abstract class TransformSettings {
            public abstract Property<Boolean> getTransformSources();

            public abstract Property<org.gradle.api.specs.Spec<String>> getTransformedSourceExtensions();

            @Inject
            public TransformSettings() {
                getTransformSources().convention(false);
                getTransformedSourceExtensions().convention(new DefaultExtensionSpec());
            }
        }

        // Can't be a lambda or local class because gradle serialization doesn't like it
        private static class DefaultExtensionSpec implements Spec<String> {
            private static final Set<String> DEFAULTS = Set.of("java", "groovy", "scala", "kt");

            @Override
            public boolean isSatisfiedBy(String s) {
                return DEFAULTS.contains(s);
            }
        }
    }

    @SuppressWarnings({"DataFlowIssue", "unchecked", "rawtypes"})
    private static void copyAttributes(Configuration original, Configuration target) {
        for (var attribute : original.getAttributes().keySet()) {
            target.getAttributes().attribute((Attribute) attribute, original.getAttributes().getAttribute(attribute));
        }
    }
}
