package org.qubership.integration.platform.ai.plan.mapping;

import groovy.json.JsonBuilder;
import groovy.json.JsonOutput;
import groovy.json.JsonSlurper;
import groovy.lang.GString;
import groovy.lang.GroovyClassLoader;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.codehaus.groovy.ast.AnnotatedNode;
import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.MethodPointerExpression;
import org.codehaus.groovy.ast.expr.MethodReferenceExpression;
import org.codehaus.groovy.classgen.GeneratorContext;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.customizers.SecureASTCustomizer;
import org.codehaus.groovy.runtime.MethodClosure;

/**
 * Compiles mapping Groovy under a secure AST policy. Does not run the script.
 */
public final class SecureGroovyMappingCompiler {

  private static final String PREFIX = "Groovy mapping:";
  private static final String GRAB_TRANSFORMATION = "groovy.grape.GrabAnnotationTransformation";
  private static final Set<String> FORBIDDEN_ANNOTATIONS =
      Set.of("groovy.lang.Grab", "groovy.lang.GrabConfig", "groovy.lang.Grapes");

  private SecureGroovyMappingCompiler() {}

  public static void compile(String script) {
    Objects.requireNonNull(script, "script");
    CompilerConfiguration config = new CompilerConfiguration();
    Set<String> disabled = new LinkedHashSet<>();
    if (config.getDisabledGlobalASTTransformations() != null) {
      disabled.addAll(config.getDisabledGlobalASTTransformations());
    }
    disabled.add(GRAB_TRANSFORMATION);
    config.setDisabledGlobalASTTransformations(disabled);
    config.addCompilationCustomizers(secureAst());
    try (GroovyClassLoader loader =
        new GroovyClassLoader(SecureGroovyMappingCompiler.class.getClassLoader(), config)) {
      loader.parseClass(script);
    } catch (CompilationFailedException | SecurityException e) {
      throw new IllegalArgumentException(PREFIX + " " + e.getMessage(), e);
    } catch (IOException e) {
      throw new IllegalStateException("Cannot close the Groovy class loader", e);
    }
  }

  private static SecureASTCustomizer secureAst() {
    MappingSecureAst customizer = new MappingSecureAst();
    customizer.setIndirectImportCheckEnabled(true);
    customizer.setAllowedReceiversClasses(
        List.of(
            Object.class,
            Map.class,
            String.class,
            GString.class,
            JsonSlurper.class,
            JsonBuilder.class,
            JsonOutput.class,
            List.class,
            Collection.class,
            ArrayList.class,
            Date.class,
            LocalDate.class,
            Number.class,
            Integer.class,
            Boolean.class));
    customizer.setDisallowedStarImports(List.of("java.io.*", "java.net.*"));
    customizer.setDisallowedImports(
        List.of(
            "java.lang.Process",
            "groovy.lang.Grab",
            "groovy.lang.GrabConfig",
            "groovy.util.Eval",
            MethodClosure.class.getName()));
    customizer.setDisallowedExpressions(
        List.of(MethodPointerExpression.class, MethodReferenceExpression.class));
    customizer.addExpressionCheckers(MappingSecureAst::processExecuteAllowed);
    return customizer;
  }

  private static final class MappingSecureAst extends SecureASTCustomizer {
    @Override
    public void call(SourceUnit source, GeneratorContext context, ClassNode classNode)
        throws CompilationFailedException {
      rejectForbiddenAnnotations(classNode);
      super.call(source, context, classNode);
    }

    private static boolean processExecuteAllowed(Expression expression) {
      if (!(expression instanceof MethodCallExpression call)) {
        return true;
      }
      return !"execute".equals(call.getMethodAsString());
    }

    private static void rejectForbiddenAnnotations(ClassNode classNode) {
      new ClassCodeVisitorSupport() {
        @Override
        protected SourceUnit getSourceUnit() {
          return null;
        }

        @Override
        public void visitAnnotations(AnnotatedNode node) {
          for (AnnotationNode annotation : node.getAnnotations()) {
            String name = annotation.getClassNode().getName();
            if (FORBIDDEN_ANNOTATIONS.contains(name)) {
              throw new SecurityException("Annotation [" + name + "] is not allowed");
            }
          }
          super.visitAnnotations(node);
        }
      }.visitClass(classNode);
    }
  }
}
