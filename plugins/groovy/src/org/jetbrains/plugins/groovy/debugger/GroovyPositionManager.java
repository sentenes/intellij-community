/*
 * Copyright 2000-2007 JetBrains s.r.o.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.plugins.groovy.debugger;

import com.intellij.debugger.PositionManager;
import com.intellij.debugger.SourcePosition;
import com.intellij.debugger.engine.CompoundPositionManager;
import com.intellij.debugger.engine.DebugProcess;
import com.intellij.debugger.engine.DebugProcessImpl;
import com.intellij.debugger.engine.DebuggerUtils;
import com.intellij.debugger.jdi.VirtualMachineProxyImpl;
import com.intellij.debugger.requests.ClassPrepareRequestor;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.Location;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.request.ClassPrepareRequest;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.groovy.lang.psi.GroovyFile;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.typedef.GrTypeDefinition;
import org.jetbrains.plugins.groovy.caches.module.GroovyModuleCachesManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class GroovyPositionManager implements PositionManager {
  private static final Logger LOG = Logger.getInstance("#com.intellij.debugger.engine.PositionManagerImpl");

  private final DebugProcessImpl myDebugProcess;

  public GroovyPositionManager(DebugProcessImpl debugProcess) {
    myDebugProcess = debugProcess;
  }

  public DebugProcess getDebugProcess() {
    return myDebugProcess;
  }

  public List<Location> locationsOfLine(ReferenceType type,
                                        SourcePosition position) {
    try {
      int line = position.getLine() + 1;
      return getDebugProcess().getVirtualMachineProxy().versionHigher("1.4") ? type.locationsOfLine(DebugProcessImpl.JAVA_STRATUM, null, line) : type.locationsOfLine(line);
    }
    catch (AbsentInformationException e) {
        return Collections.emptyList();
    }
  }

  private GrTypeDefinition findTypeDefinition(SourcePosition position) {
    PsiFile file = position.getFile();
    if (!(file instanceof GroovyFile)) return null;
    PsiElement element = file.findElementAt(position.getOffset());
    if (element == null) return null;
    return PsiTreeUtil.getParentOfType(element, GrTypeDefinition.class);
  }

  private GrTypeDefinition getToplevelTypeDefinition (GrTypeDefinition inner) {
    GrTypeDefinition outer = PsiTreeUtil.getParentOfType(inner, GrTypeDefinition.class);
    while(outer != null) {
      if (outer.getQualifiedName() != null) return outer;
      outer = PsiTreeUtil.getParentOfType(inner, GrTypeDefinition.class);
    }

    return null;
  }

  public ClassPrepareRequest createPrepareRequest(final ClassPrepareRequestor requestor, final SourcePosition position) {
    GrTypeDefinition typeDefinition = findTypeDefinition(position);
    if (typeDefinition == null) return null;
    String qName = typeDefinition.getQualifiedName();

    String waitPrepareFor;
    ClassPrepareRequestor waitRequestor;

    if(qName == null) {
      GrTypeDefinition toplevel = getToplevelTypeDefinition(typeDefinition);

      if(toplevel == null) return null;

      final String toplevelQName = toplevel.getQualifiedName();
      if (toplevelQName == null) return null;
      waitPrepareFor = toplevelQName + "$*";
      waitRequestor = new ClassPrepareRequestor() {
        public void processClassPrepare(DebugProcess debuggerProcess, ReferenceType referenceType) {
          final CompoundPositionManager positionManager = ((DebugProcessImpl)debuggerProcess).getPositionManager();
          if (positionManager.locationsOfLine(referenceType, position).size() > 0) {
            requestor.processClassPrepare(debuggerProcess, referenceType);
          }
          else {
            final List<ReferenceType> positionClasses = positionManager.getAllClasses(position);
            if (positionClasses.contains(referenceType)) {
              requestor.processClassPrepare(debuggerProcess, referenceType);
            }
          }
        }
      };
    }
    else {
      waitPrepareFor = qName;
      waitRequestor = requestor;
    }

    return myDebugProcess.getRequestsManager().createClassPrepareRequest(waitRequestor, waitPrepareFor);
  }

  public SourcePosition getSourcePosition(final Location location) {
    if(location == null) return null;

    PsiFile psiFile = getPsiFileByLocation(getDebugProcess().getProject(), location);
    if(psiFile == null ) return null;

    int lineNumber  = calcLineIndex(psiFile, location);
    if (lineNumber < 0) return null;
    return SourcePosition.createFromLine(psiFile, lineNumber);
  }

  private int calcLineIndex(PsiFile psiFile,
                            Location location) {
    LOG.assertTrue(myDebugProcess != null);
    if (location == null) return -1;

    try {
      return location.lineNumber() - 1;
    }
    catch (InternalError e) {
      return -1;
    }
  }

  @Nullable
  private PsiFile getPsiFileByLocation(final Project project, final Location location) {
    if (location == null) return null;

    final ReferenceType refType = location.declaringType();
    if (refType == null) return null;

    final String originalQName = refType.name();
    int dollar = originalQName.indexOf('$');
    final String qName = dollar >= 0 ? originalQName.substring(0, dollar) : originalQName;
    final GlobalSearchScope searchScope = myDebugProcess.getSession().getSearchScope();

    Module[] modules = ModuleManager.getInstance(project).getModules();
    for (Module module : modules) {
      GroovyModuleCachesManager manager = GroovyModuleCachesManager.getInstance(module);
      GrTypeDefinition typeDefinition = manager.getModuleFilesCache().getClassByName(qName);
      if (typeDefinition != null) {
        return typeDefinition.getContainingFile();
      }
    }

    return null;
  }

  public List<ReferenceType> getAllClasses(final SourcePosition classPosition) {
    return ApplicationManager.getApplication().runReadAction(new Computable<List<ReferenceType>> () {
      public List<ReferenceType> compute() {
        GrTypeDefinition typeDefinition = findTypeDefinition(classPosition);

        if(typeDefinition == null) return Collections.emptyList();

        String qName = typeDefinition.getQualifiedName();
        if(qName == null) {
          final GrTypeDefinition toplevel = getToplevelTypeDefinition(typeDefinition);
          if(toplevel == null) return Collections.emptyList();

          final String parentClassName = toplevel.getQualifiedName();

          final List<ReferenceType> outers = myDebugProcess.getVirtualMachineProxy().classesByName(parentClassName);
          final List<ReferenceType> result = new ArrayList<ReferenceType>(outers.size());

          for (ReferenceType outer : outers) {
            final ReferenceType nested = findNested(outer, typeDefinition, classPosition);
            if (nested != null) {
              result.add(nested);
            }
          }
          return result;
        }
        else {
          return myDebugProcess.getVirtualMachineProxy().classesByName(qName);
        }
      }
    });
  }

  @Nullable
  private ReferenceType findNested(ReferenceType fromClass, final GrTypeDefinition typeDefinitionToFind, SourcePosition classPosition) {
    final VirtualMachineProxyImpl vmProxy = myDebugProcess.getVirtualMachineProxy();
    if (fromClass.isPrepared()) {

      final List<ReferenceType> nestedTypes = vmProxy.nestedTypes(fromClass);

      for (ReferenceType nested : nestedTypes) {
        final ReferenceType found = findNested(nested, typeDefinitionToFind, classPosition);
        if (found != null) {
          return found;
        }
      }

      try {
        final int lineNumber = classPosition.getLine() + 1;
        if (fromClass.locationsOfLine(lineNumber).size() > 0) {
          return fromClass;
        }
        //noinspection LoopStatementThatDoesntLoop
        for (Location location : fromClass.allLineLocations()) {
          final SourcePosition candidateFirstPosition = SourcePosition.createFromLine(typeDefinitionToFind.getContainingFile(), location.lineNumber() - 1);
          if (typeDefinitionToFind.equals(findTypeDefinition(candidateFirstPosition))) {
            return fromClass;
          }
          break; // check only the first location
        }
      }
      catch (AbsentInformationException ignored) {
      }
    }
    return null;
  }

}