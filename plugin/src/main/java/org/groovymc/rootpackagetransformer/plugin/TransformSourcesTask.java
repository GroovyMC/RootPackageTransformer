package org.groovymc.rootpackagetransformer.plugin;

import org.gradle.api.Action;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.CopySpec;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.specs.Spec;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.groovymc.rootpackagetransformer.transform.RootPackageTransformer;

import javax.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;

@CacheableTask
public abstract class TransformSourcesTask extends DefaultTask {
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getListFile();

    @Nested
    public abstract Property<Action<CopySpec>> getCopyConfiguration();

    @OutputDirectory
    public abstract DirectoryProperty getDestinationDirectory();

    @Nested
    public abstract Property<Spec<String>> getTransformedExtensions();

    @Inject
    public TransformSourcesTask() {
        getDestinationDirectory().convention(getProject().getLayout().getBuildDirectory().dir("transformed/"+getName()));
    }

    @Inject
    protected abstract FileSystemOperations getFileSystemOperations();

    @TaskAction
    protected void run() {
        if (getDestinationDirectory().getAsFile().get().exists()) {
            getFileSystemOperations().delete(spec -> spec.delete(getDestinationDirectory().get()));
        }

        getFileSystemOperations().copy(spec -> {
            getCopyConfiguration().get().execute(spec);
            spec.into(getDestinationDirectory());
        });

        try {
            var transformer = new RootPackageTransformer(getListFile().get().getAsFile().toPath());
            transformer.applyToAllInDirectory(getDestinationDirectory().getAsFile().get().toPath(), getTransformedExtensions().get()::isSatisfiedBy);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
