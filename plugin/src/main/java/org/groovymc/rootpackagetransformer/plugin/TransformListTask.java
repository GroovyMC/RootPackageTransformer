package org.groovymc.rootpackagetransformer.plugin;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.groovymc.rootpackagetransformer.transform.RootPackageTransformer;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;

@CacheableTask
public abstract class TransformListTask extends DefaultTask {
    @InputFiles
    @PathSensitive(PathSensitivity.NONE)
    public abstract ConfigurableFileCollection getInputFiles();

    @OutputFile
    public abstract RegularFileProperty getListFile();

    @Inject
    public TransformListTask() {
        getListFile().convention(getProject().getLayout().getBuildDirectory().file("transformed/"+getName()+"/org.groovymc.rootpackagetransformer.transformedclasses"));
    }

    @TaskAction
    protected void run() throws IOException {
        var transformer = RootPackageTransformer.classesToMove(getInputFiles().getFiles().stream().map(File::toPath).toList());
        transformer.writeTargetClasses(getListFile().getAsFile().get().toPath());
    }
}
