package com.twitter.intellij.pants.service.project;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.*;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.externalSystem.model.DataNode;
import com.intellij.openapi.externalSystem.model.ExternalSystemException;
import com.intellij.openapi.externalSystem.model.ProjectKeys;
import com.intellij.openapi.externalSystem.model.project.*;
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId;
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener;
import com.intellij.openapi.module.ModuleTypeId;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.Function;
import com.intellij.util.containers.ContainerUtil;
import com.twitter.intellij.pants.PantsException;
import com.twitter.intellij.pants.service.project.model.ProjectInfo;
import com.twitter.intellij.pants.service.project.model.SourceRoot;
import com.twitter.intellij.pants.service.project.model.TargetInfo;
import com.twitter.intellij.pants.settings.PantsExecutionSettings;
import com.twitter.intellij.pants.util.PantsConstants;
import com.twitter.intellij.pants.util.PantsSourceType;
import com.twitter.intellij.pants.util.PantsUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class PantsResolver {
  private static final Logger LOG = Logger.getInstance(PantsResolver.class);

  private final boolean generateJars;
  private final String projectPath;
  private final PantsExecutionSettings settings;

  @Nullable
  protected File myWorkDirectory = null;
  private ProjectInfo projectInfo = null;

  @TestOnly
  public void setWorkDirectory(@Nullable File workDirectory) {
    myWorkDirectory = workDirectory;
  }

  @TestOnly
  public void setProjectInfo(ProjectInfo projectInfo) {
    this.projectInfo = projectInfo;
  }

  public PantsResolver(@NotNull String projectPath, @NotNull PantsExecutionSettings settings, boolean isPreviewMode) {
    this.projectPath = projectPath;
    this.settings = settings;
    generateJars = !isPreviewMode;
  }

  public static ProjectInfo parseProjectInfoFromJSON(String data) throws JsonSyntaxException {
    return new Gson().fromJson(data, ProjectInfo.class);
  }

  private void parse(final String output, List<String> err) {
    projectInfo = null;
    if (output.isEmpty()) throw new ExternalSystemException("Not output from pants");
    try {
      projectInfo = parseProjectInfoFromJSON(output);
    }
    catch (JsonSyntaxException e) {
      LOG.warn("Can't parse output\n" + output, e);
      throw new ExternalSystemException("Can't parse project structure!");
    }
  }

  public void addInfo(@NotNull DataNode<ProjectData> projectInfoDataNode) {
    if (projectInfo == null) return;

    final Map<String, DataNode<ModuleData>> modules = new HashMap<String, DataNode<ModuleData>>();

    // create all modules. no libs, dependencies and source roots
    for (Map.Entry<String, TargetInfo> entry : projectInfo.getTargets().entrySet()) {
      final String targetName = entry.getKey();
      if (StringUtil.startsWith(targetName, ":scala-library")) {
        // we already have it in libs
        continue;
      }
      final TargetInfo targetInfo = entry.getValue();
      if (targetInfo.isEmpty()) {
        LOG.info("Skipping " + targetName + " because it is empty");
        continue;
      }
      final DataNode<ModuleData> moduleData = createModuleData(
        projectInfoDataNode, targetName, targetInfo.getRoots(), PantsUtil.getSourceTypeForTargetType(targetInfo.getTargetType())
      );
      modules.put(targetName, moduleData);
    }

    // IntelliJ doesn't support when several modules have the same source root
    final Map<SourceRoot, DataNode<ModuleData>> modulesForRootsAndInfo = handleCommonRoots(projectInfoDataNode, modules);

    // source roots
    for (Map.Entry<String, TargetInfo> entry : projectInfo.getTargets().entrySet()) {
      final String mainTarget = entry.getKey();
      final TargetInfo targetInfo = entry.getValue();
      if (!modules.containsKey(mainTarget) || targetInfo.getRoots().isEmpty()) {
        continue;
      }
      final DataNode<ModuleData> moduleDataNode = modules.get(mainTarget);
      final ContentRootData contentRoot = findChildData(moduleDataNode, ProjectKeys.CONTENT_ROOT);
      if (contentRoot == null) {
        LOG.warn("no content root for " + mainTarget);
        continue;
      }
      for (SourceRoot root : targetInfo.getRoots()) {
        final DataNode<ModuleData> sourceRootModule = modulesForRootsAndInfo.get(root);

        if (moduleDataNode != sourceRootModule && sourceRootModule != null) {
          // todo: is it always exported?
          addModuleDependency(moduleDataNode, sourceRootModule, true);
          continue;
        }
        addSourceRoot(contentRoot, root, targetInfo.getTargetType());
      }
    }


    // add dependencies
    for (Map.Entry<String, TargetInfo> entry : projectInfo.getTargets().entrySet()) {
      final String mainTarget = entry.getKey();
      final TargetInfo targetInfo = entry.getValue();
      if (!modules.containsKey(mainTarget)) {
        continue;
      }
      final DataNode<ModuleData> moduleDataNode = modules.get(mainTarget);
      for (String target : targetInfo.getTargets()) {
        if (!modules.containsKey(target)) {
          continue;
        }
        // todo: is it always exported?
        addModuleDependency(moduleDataNode, modules.get(target), true);
      }
    }

    // add libs
    for (Map.Entry<String, TargetInfo> entry : projectInfo.getTargets().entrySet()) {
      final String mainTarget = entry.getKey();
      final TargetInfo targetInfo = entry.getValue();
      if (!modules.containsKey(mainTarget)) {
        continue;
      }
      final DataNode<ModuleData> moduleDataNode = modules.get(mainTarget);
      for (String libraryId : targetInfo.getLibraries()) {
        // todo: is it always exported?
        createLibraryData(moduleDataNode, libraryId, true);
      }
    }

    for (String resolverClassName : settings.getResolverExtensionClassNames()) {
      try {
        Object resolver = Class.forName(resolverClassName).newInstance();
        if (resolver instanceof PantsResolverExtension) {
          ((PantsResolverExtension)resolver).resolve(projectInfo, modules);
        }
      }
      catch (Exception e) {
        LOG.error(e);
      }
    }
  }

  private void addSourceRoot(@NotNull ContentRootData contentRoot, @NotNull SourceRoot root, @Nullable String targetType) {
    try {
      final PantsSourceType rootType = PantsUtil.getSourceTypeForTargetType(targetType);
      contentRoot.storePath(
        rootType.toExternalSystemSourceType(),
        root.getSourceRootRegardingSourceType(rootType),
        StringUtil.nullize(root.getPackagePrefix())
      );
    }
    catch (IllegalArgumentException e) {
      LOG.warn(e);
      // todo(fkorotkov): log and investigate exceptions from ContentRootData.storePath(ContentRootData.java:94)
    }
  }

  @Nullable
  private <T> T findChildData(DataNode<?> dataNode, com.intellij.openapi.externalSystem.model.Key<T> key) {
    for (DataNode<?> child : dataNode.getChildren()) {
      T childData = child.getData(key);
      if (childData != null) {
        return childData;
      }
    }
    return null;
  }

  private <T> List<T> findChildren(DataNode<?> dataNode, com.intellij.openapi.externalSystem.model.Key<T> key) {
    final ArrayList<T> children = new ArrayList<T>();
    for (DataNode<?> child : dataNode.getChildren()) {
      T childData = child.getData(key);
      if (childData != null) {
         children.add(childData);
      }
    }
    return children;
  }

  private void addModuleDependency(DataNode<ModuleData> moduleDataNode, DataNode<ModuleData> submoduleDataNode, boolean exported) {
    final List<ModuleDependencyData> subModuleDeps = findChildren(submoduleDataNode, ProjectKeys.MODULE_DEPENDENCY);
    for (ModuleDependencyData dep : subModuleDeps) {
      if (dep.getTarget() == moduleDataNode.getData()) {
        LOG.debug("Found cyclic dependency between " + submoduleDataNode.getData().getInternalName() +
                  " and " + moduleDataNode.getData().getInternalName());
        return;
      }
    }
    final ModuleDependencyData moduleDependencyData = new ModuleDependencyData(
      moduleDataNode.getData(),
      submoduleDataNode.getData()
    );
    moduleDependencyData.setExported(exported);
    moduleDataNode.createChild(ProjectKeys.MODULE_DEPENDENCY, moduleDependencyData);
  }

  private Map<SourceRoot, DataNode<ModuleData>> handleCommonRoots(
    DataNode<ProjectData> projectInfoDataNode,
    Map<String, DataNode<ModuleData>> modules
  ) {
    // source root -> list<(target name, target info)>
    final Map<SourceRoot, List<Pair<String, TargetInfo>>> sourceRoot2Targets =
      new HashMap<SourceRoot, List<Pair<String, TargetInfo>>>();
    for (Map.Entry<String, TargetInfo> entry : projectInfo.getTargets().entrySet()) {
      final TargetInfo targetInfo = entry.getValue();
      for (SourceRoot sourceRoot : targetInfo.getRoots()) {
        List<Pair<String, TargetInfo>> targetInfos = sourceRoot2Targets.get(sourceRoot);
        if (targetInfos == null) {
          targetInfos = new ArrayList<Pair<String, TargetInfo>>();
          sourceRoot2Targets.put(sourceRoot, targetInfos);
        }
        targetInfos.add(Pair.create(entry.getKey(), targetInfo));
      }
    }

    final Map<SourceRoot, DataNode<ModuleData>> result = new HashMap<SourceRoot, DataNode<ModuleData>>();

    for (Map.Entry<SourceRoot, List<Pair<String, TargetInfo>>> entry : sourceRoot2Targets.entrySet()) {
      final List<Pair<String, TargetInfo>> targetNameAndInfos = entry.getValue();
      if (targetNameAndInfos.size() > 1) {
        SourceRoot sourceRoot = entry.getKey();
        final Pair<String, TargetInfo> targetWithOneRoot = ContainerUtil.find(
          targetNameAndInfos,
          new Condition<Pair<String, TargetInfo>>() {
            @Override
            public boolean value(Pair<String, TargetInfo> info) {
              return info.getSecond().getRoots().size() == 1;
            }
          }
        );

        if (targetWithOneRoot != null) {
          final DataNode<ModuleData> moduleDataDataNode = modules.get(targetWithOneRoot.getFirst());
          if (moduleDataDataNode != null) {
            LOG.debug("Found common source root target " + targetWithOneRoot.getFirst());
            result.put(sourceRoot, moduleDataDataNode);
          }
          else {
            LOG.warn("Bad common source root " + sourceRoot + " for " + targetWithOneRoot.getFirst());
          }
        } else {
          final TargetInfo firstTargetInfo = targetNameAndInfos.iterator().next().getSecond();
          final PantsSourceType rootType = PantsUtil.getSourceTypeForTargetType(firstTargetInfo.getTargetType());
          final String root = FileUtil.getRelativePath(myWorkDirectory, new File(sourceRoot.getSourceRootRegardingSourceType(rootType)));
          final DataNode<ModuleData> rootModuleData =
            createModuleData(
              projectInfoDataNode,
              sourceRoot.getPackagePrefix(),
              StringUtil.notNullize(root),
              Arrays.asList(sourceRoot),
              PantsUtil.getSourceTypeForTargetType(firstTargetInfo.getTargetType())
            );

          final ContentRootData contentRoot = findChildData(rootModuleData, ProjectKeys.CONTENT_ROOT);
          if (contentRoot != null) {
            addSourceRoot(contentRoot, sourceRoot, firstTargetInfo.getTargetType());
          }

          final Set<String> libDeps = new HashSet<String>();
          final Set<String> targetDeps = new HashSet<String>();
          for (Pair<String, TargetInfo> info : targetNameAndInfos) {
            final TargetInfo targetInfo = info.getSecond();
            targetDeps.addAll(targetInfo.getTargets());
            libDeps.addAll(targetInfo.getLibraries());
          }

          // do not export dependencies so they won't pollute classpaths of dependent modules
          for (String targetName : targetDeps) {
            final DataNode<ModuleData> dependencyModule = modules.get(targetName);
            if (dependencyModule != null) {
              addModuleDependency(rootModuleData, dependencyModule, false);
            }
          }

          for (String libraryId : libDeps) {
            createLibraryData(rootModuleData, libraryId, false);
          }

          result.put(sourceRoot, rootModuleData);
        }
      }
    }

    return result;
  }

  private void createLibraryData(@NotNull DataNode<ModuleData> moduleDataNode, String libraryId, boolean exported) {
    if (StringUtil.startsWith(libraryId, "org.scala-lang:scala-library")) {
      // skip Scala. Will be added by ScalaPantsDataService
      return;
    }
    final List<String> libraryJars = projectInfo.getLibraries(libraryId);
    if (libraryJars.isEmpty() && generateJars) {
      // log only we tried to resolve libs
      LOG.warn("No info for library: " + libraryId);
    }
    if (libraryJars.isEmpty()) {
      return;
    }
    final LibraryData libraryData = new LibraryData(PantsConstants.SYSTEM_ID, libraryId);
    for (String jarPath : libraryJars) {
      // todo: sources + docs
      libraryData.addPath(LibraryPathType.BINARY, jarPath);
    }
    final LibraryDependencyData library = new LibraryDependencyData(
      moduleDataNode.getData(),
      libraryData,
      LibraryLevel.MODULE
    );
    library.setExported(exported);
    moduleDataNode.createChild(ProjectKeys.LIBRARY_DEPENDENCY, library);
  }

  private DataNode<ModuleData> createModuleData(
    DataNode<ProjectData> projectInfoDataNode,
    String targetName,
    List<SourceRoot> roots,
    @Nullable PantsSourceType rootType
  ) {
    final int index = targetName.lastIndexOf(':');
    final String path = targetName.substring(0, index);
    return createModuleData(projectInfoDataNode, targetName, path, roots, rootType);
  }

  private DataNode<ModuleData> createModuleData(
    DataNode<ProjectData> projectInfoDataNode,
    String targetName,
    @NotNull String path,
    List<SourceRoot> roots,
    @Nullable final PantsSourceType rootType
  ) {
    final String contentRootPath = StringUtil.notNullize(
      PantsUtil.findCommonRoot(
        ContainerUtil.map(
          roots,
          new Function<SourceRoot, String>() {
            @Override
            public String fun(SourceRoot root) {
              return root.getSourceRootRegardingSourceType(rootType);
            }
          }
        )
      ),
      path
    );

    final File[] files = myWorkDirectory != null ? new File(myWorkDirectory, path).listFiles() : null;
    final File BUILDFile = files == null ? null : ContainerUtil.find(
      files,
      new Condition<File>() {
        @Override
        public boolean value(File file) {
          return PantsUtil.isBUILDFileName(file.getName());
        }
      }
    );

    final String moduleName = PantsUtil.getCanonicalModuleName(targetName);

    final ModuleData moduleData = new ModuleData(
      moduleName,
      PantsConstants.SYSTEM_ID,
      ModuleTypeId.JAVA_MODULE,
      moduleName,
      projectInfoDataNode.getData().getIdeProjectFileDirectoryPath() + "/" + moduleName,
      StringUtil.notNullize(
        BUILDFile == null ? null : FileUtil.getRelativePath(myWorkDirectory, BUILDFile),
        path
      )
    );

    final DataNode<ModuleData> moduleDataNode = projectInfoDataNode.createChild(ProjectKeys.MODULE, moduleData);

    if (!roots.isEmpty()) {
      final ContentRootData contentRoot = new ContentRootData(
        PantsConstants.SYSTEM_ID,
        contentRootPath
      );
      moduleDataNode.createChild(ProjectKeys.CONTENT_ROOT, contentRoot);
    }

    return moduleDataNode;
  }

  public void resolve(final ExternalSystemTaskId taskId, final ExternalSystemTaskNotificationListener listener) {
    try {
      final File outputFile = FileUtil.createTempFile("pants_run", ".out");
      final GeneralCommandLine command = getCommand(outputFile);
      final Process process = command.createProcess();
      final CapturingProcessHandler processHandler = new CapturingProcessHandler(process);
      processHandler.addProcessListener(
        new ProcessAdapter() {
          @Override
          public void onTextAvailable(ProcessEvent event, Key outputType) {
            listener.onTaskOutput(taskId, event.getText(), outputType == ProcessOutputTypes.STDOUT);
          }
        }
      );
      final ProcessOutput processOutput = processHandler.runProcess();
      if (processOutput.getStdout().contains("no such option")) {
        throw new ExternalSystemException("Pants doesn't have necessary APIs. Please upgrade you pants!");
      }
      if (processOutput.checkSuccess(LOG)) {
        final String output = FileUtil.loadFile(outputFile);
        parse(output, processOutput.getStderrLines());
      }
      else {
        throw new ExternalSystemException(
          "Failed to update the project!\n\n" + processOutput.getStdout() + "\n\n" + processOutput.getStderr()
        );
      }
    }
    catch (ExecutionException e) {
      throw new ExternalSystemException(e);
    }
    catch (IOException ioException) {
      throw new ExternalSystemException(ioException);
    }
  }

  protected GeneralCommandLine getCommand(final File outputFile) {
    try {
      final GeneralCommandLine commandLine = PantsUtil.defaultCommandLine(projectPath);
      myWorkDirectory = commandLine.getWorkDirectory();
      commandLine.addParameter("goal");
      if (generateJars) {
        commandLine.addParameter("resolve");
      }
      final File workDirectory = commandLine.getWorkDirectory();
      final File projectFile = new File(projectPath);
      final String relativeProjectPath =
        FileUtil.getRelativePath(workDirectory, projectFile.isDirectory() ? projectFile : projectFile.getParentFile());

      if (relativeProjectPath == null) {
        throw new ExternalSystemException(
          String.format("Can't find relative path for a target %s from dir %s", projectPath, workDirectory.getPath())
        );
      }

      commandLine.addParameter("depmap");
      if (settings.isAllTargets()) {
        commandLine.addParameter(relativeProjectPath + File.separator + "::");
      }
      else {
        for (String targetName : settings.getTargetNames()) {
          commandLine.addParameter(relativeProjectPath + File.separator + ":" + targetName);
        }
      }
      commandLine.addParameter("--depmap-project-info");
      commandLine.addParameter("--depmap-project-info-formatted");
      commandLine.addParameter("--depmap-output-file=" + outputFile.getPath());
      return commandLine;
    }
    catch (PantsException exception) {
      throw new ExternalSystemException(exception);
    }
  }
}
