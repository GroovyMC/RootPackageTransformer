package org.groovymc.rootpackagetransformer.transform;

import org.groovymc.rootpackagetransformer.RootPackage;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

public final class RootPackageTransformer implements UnaryOperator<String> {
    private static final String ROOT_PACKAGE_DESCRIPTOR = Type.getDescriptor(RootPackage.class);

    public static RootPackageTransformer classesToMove(Collection<Path> paths) throws IOException {
        Set<String> classes = new HashSet<>();
        for (Path classFile : paths) {
            if (!Files.isRegularFile(classFile) || !classFile.toString().endsWith(".class")) {
                continue;
            }
            try (var is = Files.newInputStream(classFile)) {
                ClassReader reader = new ClassReader(is);
                String[] foundName = new String[1];
                boolean[] relocate = new boolean[1];
                ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                        foundName[0] = name;
                        super.visit(version, access, name, signature, superName, interfaces);
                    }

                    @Override
                    public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                        if (descriptor.equals(ROOT_PACKAGE_DESCRIPTOR)) {
                            relocate[0] = true;
                        }
                        return super.visitAnnotation(descriptor, visible);
                    }
                };
                reader.accept(visitor, 0);
                if (relocate[0]) {
                    classes.add(foundName[0]);
                }
            }
        }
        return new RootPackageTransformer(classes);
    }

    private final List<Replacement> rewrite;
    private final List<String> classes;
    private final Set<String> classesSet;

    public RootPackageTransformer(Collection<String> classes) {
        this.rewrite = new ArrayList<>();
        var classesList = new ArrayList<String>();
        for (String clazz : classes) {
            String dotName = clazz.replace('/', '.');
            String slashName = clazz.replace('.', '/');
            String simpleName = slashName.substring(slashName.lastIndexOf('/') + 1);
            rewrite.add(new Replacement(dotName, simpleName));
            rewrite.add(new Replacement(slashName, simpleName));
            classesList.add(clazz);
        }
        rewrite.sort(Comparator.comparing(replacement -> replacement.target().length()));
        classesList.sort(Comparator.naturalOrder());
        this.classes = Collections.unmodifiableList(classesList);
        this.classesSet = Set.copyOf(classes);
    }

    public RootPackageTransformer(Path path) throws IOException {
        this(Files.readAllLines(path));
    }

    public void writeTargetClasses(Path path) throws IOException {
        var sorted = new ArrayList<>(this.classes);
        Files.write(path, sorted, StandardOpenOption.CREATE);
    }

    @Override
    public String apply(String s) {
        int l = s.length();
        for (var entry : this.rewrite) {
            if (entry.target().length() > l) {
                break;
            }
            if (s.equals(entry.target())) {
                return entry.replacement();
            }
            s = s.replace(entry.target(), entry.replacement());
        }
        return s;
    }

    public void applyToAllInDirectory(Path directory, Predicate<String> validExtension) throws IOException {
        try (var stream = Files.walk(directory)) {
            var paths = stream.filter(Files::isRegularFile).filter(p -> {
                var name = p.getFileName().toString();
                var extension = name.lastIndexOf('.');
                if (extension == -1) {
                    return false;
                }
                return validExtension.test(name.substring(extension + 1));
            }).toList();
            for (Path path : paths) {
                applyTo(path, directory);
            }
        }
    }

    private void applyTo(Path path, Path directory) throws IOException {
        var relativePath = directory.relativize(path);
        String[] names = new String[relativePath.getNameCount()];
        for (int i = 0; i < names.length; i++) {
            names[i] = relativePath.getName(i).toString();
        }
        String full = String.join("/", names);
        String extension = full.substring(full.lastIndexOf('.') + 1);
        String className = full.substring(0, full.length() - extension.length() - 1);
        String contents = Files.readString(path);
        String rewritten = apply(contents);
        String newClassName = className;
        if (this.classesSet.contains(className)) {
            newClassName = apply(className);
            if (rewritten.trim().startsWith("package ")) {
                int end = rewritten.indexOf(';');
                if (end != -1) {
                    rewritten = rewritten.substring(end + 1);
                } else {
                    int line = rewritten.indexOf('\n');
                    if (line != -1) {
                        rewritten = rewritten.substring(line + 1);
                    }
                }
            }
        }
        if (!contents.equals(rewritten) || !className.equals(newClassName)) {
            Files.delete(path);
            Files.writeString(directory.resolve(newClassName+'.'+extension), rewritten, StandardOpenOption.CREATE);
        }
    }

    private record Replacement(String target, String replacement) {}
}
