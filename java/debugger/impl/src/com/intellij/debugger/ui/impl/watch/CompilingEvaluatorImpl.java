// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.debugger.ui.impl.watch;

import com.intellij.compiler.CompilerConfiguration;
import com.intellij.compiler.server.BuildManager;
import com.intellij.debugger.engine.DebugProcessImpl;
import com.intellij.debugger.engine.SuspendContextImpl;
import com.intellij.debugger.engine.evaluation.EvaluateException;
import com.intellij.debugger.engine.evaluation.expression.ExpressionEvaluator;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.compiler.ClassObject;
import com.intellij.openapi.compiler.CompilationException;
import com.intellij.openapi.compiler.CompilerManager;
import com.intellij.openapi.compiler.CompilerMessageCategory;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.JavaSdkVersion;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.ThrowableComputable;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.psi.PsiCodeFragment;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.refactoring.extractMethod.PrepareFailedException;
import com.intellij.refactoring.extractMethodObject.ExtractLightMethodObjectHandler;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebuggerManager;
import com.intellij.xdebugger.frame.XSuspendContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jps.incremental.java.JavaBuilder;
import org.jetbrains.jps.model.java.JpsJavaSdkType;
import org.jetbrains.jps.model.java.compiler.AnnotationProcessingConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.function.Function;

// todo: consider batching compilations in order not to start a separate process for every class that needs to be compiled
public class CompilingEvaluatorImpl extends CompilingEvaluator {
  private static final Logger LOG = Logger.getInstance(CompilingEvaluatorImpl.class);
  private Collection<ClassObject> myCompiledClasses;
  private final Module myModule;

  public CompilingEvaluatorImpl(@NotNull Project project,
                                @NotNull PsiElement context,
                                @NotNull ExtractLightMethodObjectHandler.ExtractedData data) {
    super(project, context, data);
    myModule = ModuleUtilCore.findModuleForPsiElement(context);
  }

  @Override
  @NotNull
  protected Collection<ClassObject> compile(@Nullable JavaSdkVersion debuggeeVersion) throws EvaluateException {
    if (myCompiledClasses == null) {
      List<String> options = new ArrayList<>();
      options.add("-encoding");
      options.add("UTF-8");
      List<File> platformClasspath = new ArrayList<>();
      List<File> classpath = new ArrayList<>();
      AnnotationProcessingConfiguration profile = null;
      if (myModule != null) {
        assert myProject.equals(myModule.getProject()) : myModule + " is from another project";
        profile = CompilerConfiguration.getInstance(myProject).getAnnotationProcessingConfiguration(myModule);
        ModuleRootManager rootManager = ModuleRootManager.getInstance(myModule);
        for (String s : rootManager.orderEntries().compileOnly().recursively().exportedOnly().withoutSdk().getPathsList().getPathList()) {
          classpath.add(new File(s));
        }
        for (String s : rootManager.orderEntries().compileOnly().sdkOnly().getPathsList().getPathList()) {
          platformClasspath.add(new File(s));
        }
      }
      JavaBuilder.addAnnotationProcessingOptions(options, profile);

      Pair<Sdk, JavaSdkVersion> runtime = BuildManager.getJavacRuntimeSdk(myProject);
      JavaSdkVersion buildRuntimeVersion = runtime.getSecond();
      // if compiler or debuggee version or both are unknown, let source and target be the compiler's defaults
      if (buildRuntimeVersion != null && debuggeeVersion != null) {
        JavaSdkVersion minVersion = debuggeeVersion.compareTo(buildRuntimeVersion) < 0 ? debuggeeVersion : buildRuntimeVersion;
        String sourceOption = JpsJavaSdkType.complianceOption(minVersion.getMaxLanguageLevel().toJavaVersion());
        options.add("-source");
        options.add(sourceOption);
        options.add("-target");
        options.add(sourceOption);
      }

      CompilerManager compilerManager = CompilerManager.getInstance(myProject);

      File sourceFile = null;
      try {
        sourceFile = generateTempSourceFile(compilerManager.getJavacCompilerWorkingDir());
        File srcDir = sourceFile.getParentFile();
        List<File> sourcePath = Collections.emptyList();
        Set<File> sources = Collections.singleton(sourceFile);

        myCompiledClasses =
          compilerManager.compileJavaCode(options, platformClasspath, classpath, Collections.emptyList(), Collections.emptyList(), sourcePath, sources, srcDir);
      }
      catch (CompilationException e) {
        StringBuilder res = new StringBuilder("Compilation failed:\n");
        boolean errorsFound = false;
        for (CompilationException.Message m : e.getMessages()) {
          if (m.getCategory() == CompilerMessageCategory.ERROR) {
            res.append(m.getText()).append("\n");
            errorsFound = true;
          }
        }

        if (!errorsFound) {
          Collection<CompilationException.Message> messages = e.getMessages();
          LOG.warn("Compilation failed without error messages. All message are listed below. Count: " + messages.size());
          messages.forEach(x -> LOG.warn(x.getText()));
        }
        throw new EvaluateException(res.toString());
      }
      catch (Exception e) {
        throw new EvaluateException(e.getMessage());
      }
      finally {
        if (sourceFile != null) {
          FileUtil.delete(sourceFile);
        }
      }
    }
    return myCompiledClasses;
  }

  private File generateTempSourceFile(File workingDir) throws IOException {
    Pair<String, String> fileData = ReadAction.compute(() -> {
      PsiFile file = myData.getGeneratedInnerClass().getContainingFile();
      return Pair.create(file.getName(), file.getText());
    });
    if (fileData.first == null) {
      throw new IOException("Class file name not specified");
    }
    if (fileData.second == null) {
      throw new IOException("Class source code not specified");
    }
    File file = new File(workingDir, "debugger/src/" + fileData.first);
    FileUtil.writeToFile(file, fileData.second);
    return file;
  }

  @Nullable
  public static ExpressionEvaluator create(@NotNull Project project,
                                           @Nullable PsiElement psiContext,
                                           @NotNull Function<? super PsiElement, ? extends PsiCodeFragment> fragmentFactory)
    throws EvaluateException {
    if (Registry.is("debugger.compiling.evaluator") && psiContext != null) {
      return ApplicationManager.getApplication().runReadAction((ThrowableComputable<ExpressionEvaluator, EvaluateException>)() -> {
        try {
          XDebugSession currentSession = XDebuggerManager.getInstance(project).getCurrentSession();
          JavaSdkVersion javaVersion = getJavaVersion(currentSession);
          ExtractLightMethodObjectHandler.ExtractedData data = ExtractLightMethodObjectHandler.extractLightMethodObject(
            project,
            findPhysicalContext(psiContext),
            fragmentFactory.apply(psiContext),
            getGeneratedClassName(),
            javaVersion);
          if (data != null) {
            return new CompilingEvaluatorImpl(project, psiContext, data);
          }
        }
        catch (PrepareFailedException e) {
          NodeDescriptorImpl.LOG.info(e);
        }
        return null;
      });
    }
    return null;
  }

  @NotNull
  private static PsiElement findPhysicalContext(@NotNull PsiElement element) {
    while (!element.isPhysical()) {
      PsiElement context = element.getContext();
      if (context == null) {
        break;
      }
      element = context;
    }
    return element;
  }

  @Nullable
  public static JavaSdkVersion getJavaVersion(@Nullable XDebugSession session) {
    if (session != null) {
      XSuspendContext suspendContext = session.getSuspendContext();
      if (suspendContext instanceof SuspendContextImpl) {
        DebugProcessImpl debugProcess = ((SuspendContextImpl)suspendContext).getDebugProcess();
        return JavaSdkVersion.fromVersionString(debugProcess.getVirtualMachineProxy().version());
      }
    }

    return null;
  }
}