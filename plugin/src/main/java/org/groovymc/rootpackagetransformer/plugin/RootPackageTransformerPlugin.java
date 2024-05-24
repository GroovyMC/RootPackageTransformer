package org.groovymc.rootpackagetransformer.plugin;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.type.ArtifactTypeDefinition;
import org.gradle.api.attributes.Attribute;
import org.gradle.api.attributes.LibraryElements;
import org.gradle.api.attributes.Usage;
import org.gradle.api.component.AdhocComponentWithVariants;
import org.gradle.api.tasks.Copy;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.bundling.Jar;

import javax.inject.Inject;

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

        public void forSourceSet(SourceSet sourceSet, String capability) {
            var transform = project.getTasks().register(sourceSet.getTaskName("rootPackageTransform", ""), TransformTask.class, task -> {
                var dirs = sourceSet.getOutput().getClassesDirs();
                task.dependsOn(dirs.getBuildDependencies());
                dirs.forEach(f -> task.getInputFiles().from(project.fileTree(f)));
            });

            var originalManifestName = sourceSet.getTaskName("rootPackageOriginalManifest", "");
            var manifestDir = project.getLayout().getBuildDirectory().dir("extracted/"+originalManifestName);

            var originalManifest = project.getTasks().register(originalManifestName, Copy.class, task -> {
                var jar = (Jar) project.getTasks().getByName("jar");
                task.dependsOn(jar);
                task.from(project.zipTree(jar.getArchiveFile()));
                task.include("META-INF/MANIFEST.MF");
                task.into(manifestDir);
            });

            project.getTasks().named(sourceSet.getTaskName("jar", ""), Jar.class, task -> {
                task.from(transform.get().getListFile(), spec -> {
                    spec.into("META-INF");
                });
            });

            var rootPackageJar = project.getTasks().register(sourceSet.getTaskName("rootPackageJar", ""), Jar.class, task -> {
                task.dependsOn("rootPackageTransform");
                task.from(transform.get().getOutputDirectory());
                task.getArchiveClassifier().set("rootpackage");
                task.from(sourceSet.getOutput().getResourcesDir());

                task.from(transform.get().getListFile(), spec -> {
                    spec.into("META-INF");
                });

                task.manifest(m -> m.from(manifestDir.get().file("META-INF/MANIFEST.MF")));
                task.dependsOn(originalManifest);
            });

            var rootPackageElements = project.getConfigurations().maybeCreate(sourceSet.getTaskName("rootPackageElements", ""));
            rootPackageElements.setCanBeConsumed(true);
            rootPackageElements.setCanBeResolved(false);
            var runtimeElements = project.getConfigurations().getByName(sourceSet.getTaskName("runtimeElements", ""));
            rootPackageElements.getDependencies().addAllLater(project.provider(runtimeElements::getDependencies));
            copyAttributes(runtimeElements, rootPackageElements);
            rootPackageElements.getOutgoing().capability(capability);
            project.artifacts(artifactHandler ->
                    artifactHandler.add(rootPackageElements.getName(), rootPackageJar.get())
            );

            rootPackageElements.getOutgoing().getVariants().create("classes", variant -> {
                variant.artifact(transform.get().getOutputDirectory(), artifact -> {
                    artifact.setType(ArtifactTypeDefinition.JVM_CLASS_DIRECTORY);
                });
                variant.attributes(attrs -> {
                    attrs.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, project.getObjects().named(LibraryElements.class, LibraryElements.CLASSES));
                });
            });

            rootPackageElements.getOutgoing().getVariants().create("resources", variant -> {
                variant.artifact(sourceSet.getOutput().getResourcesDir(), artifact -> {
                    artifact.setType(ArtifactTypeDefinition.JVM_RESOURCES_DIRECTORY);
                });
                variant.attributes(attrs -> {
                    attrs.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, project.getObjects().named(LibraryElements.class, LibraryElements.RESOURCES));
                });
            });

            AdhocComponentWithVariants javaComponent = (AdhocComponentWithVariants) project.getComponents().getByName("java");
            javaComponent.addVariantsFromConfiguration(rootPackageElements, v -> {});

            project.getTasks().named("assemble").configure(task -> {
                task.dependsOn(rootPackageJar);
            });
        }
    }

    @SuppressWarnings({"DataFlowIssue", "unchecked", "rawtypes"})
    private static void copyAttributes(Configuration original, Configuration target) {
        for (var attribute : original.getAttributes().keySet()) {
            target.getAttributes().attribute((Attribute) attribute, original.getAttributes().getAttribute(attribute));
        }
    }
}
