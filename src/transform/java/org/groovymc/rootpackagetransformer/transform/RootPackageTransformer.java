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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

    private RootPackageTransformer(Set<String> classes) {
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
    }

    public List<String> targetClasses() {
        return this.classes;
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

    private record Replacement(String target, String replacement) {}
}
