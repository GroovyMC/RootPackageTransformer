# RootPackageTransformer

This tool provides the `@RootPackage` annotation, as well as a transformer that when applied moves any class marked with that annotation to the root package.

## Use

To use, depend on the annotation:
```gradle
dependencies {
    compileOnly 'org.groovymc:rootpackagetransformer:<version>'
}
```

The gradle plugin provides a method to create an alternative published outgoing variant for a given source set feature. The created variant will have a `org.gradle.usage` value of `java-dsl`:
```gradle
plugins {
    id 'java'
    id 'org.groovymc.rootpackagetransformer' version '<version>'
}

rootPackageTransformer.forSourceSet(sourceSets.main)
```
