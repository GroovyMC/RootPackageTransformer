package org.groovymc.rootpackagetransformer;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Classes marked with this annotation will be moved to the root package, alongside any string references to the class,
 * when the transformer is applied to the compiled class files.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
public @interface RootPackage {
}
