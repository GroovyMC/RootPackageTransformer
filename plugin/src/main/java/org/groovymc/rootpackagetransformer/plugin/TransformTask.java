package org.groovymc.rootpackagetransformer.plugin;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.groovymc.rootpackagetransformer.transform.ConstantPoolRewriter;
import org.groovymc.rootpackagetransformer.transform.RootPackageTransformer;

import javax.inject.Inject;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

@CacheableTask
public abstract class TransformTask extends DefaultTask {
    @InputFiles
    @PathSensitive(PathSensitivity.NONE)
    public abstract ConfigurableFileCollection getInputFiles();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDirectory();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getListFile();

    @Inject
    public TransformTask() {
        getOutputDirectory().convention(getProject().getLayout().getBuildDirectory().dir("transformed/"+getName()));
    }

    @Inject
    protected abstract FileSystemOperations getFileSystemOperations();

    @TaskAction
    protected void run() throws IOException {
        if (getOutputDirectory().get().getAsFile().exists()) {
            getFileSystemOperations().delete(spec -> {
                spec.delete(getOutputDirectory().get());
            });
        }
        var transformer = new RootPackageTransformer(getListFile().get().getAsFile().toPath());
        for (File file : getInputFiles()) {
            try (var is = new BufferedInputStream(new FileInputStream(file))) {
                new ConstantPoolRewriter(transformer).rewrite(is, name -> {
                    var outputDir = getOutputDirectory().get().getAsFile();
                    var outputFile = new File(outputDir, name + ".class");
                    outputFile.getParentFile().mkdirs();
                    return new BufferedOutputStream(new FileOutputStream(outputFile));
                });
            }
        }
    }
}
