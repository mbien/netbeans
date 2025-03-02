/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.netbeans.modules.java.file.launcher.actions;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.netbeans.api.extexecution.base.ExplicitProcessParameters;
import org.netbeans.api.java.platform.JavaPlatform;
import org.netbeans.api.java.platform.JavaPlatformManager;
import org.netbeans.modules.java.file.launcher.SingleSourceFileUtil;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.util.BaseUtilities;
import org.openide.util.NbPreferences;
import org.openide.util.Utilities;

final class LaunchProcess implements Callable<Process> {

    private final FileObject fileObject;
    private final JPDAStart start;
    private final ExplicitProcessParameters params;

    LaunchProcess(FileObject fileObject, JPDAStart start, ExplicitProcessParameters params) {
        this.fileObject = fileObject;
        this.start = start;
        this.params = params;
    }

    @Override
    public Process call() throws Exception {
        if (start != null) {
            return setupProcess(start.execute());
        } else {
            return setupProcess(null);
        }
    }

    private Process setupProcess(String port) throws InterruptedException {
        try {
            boolean compile = SingleSourceFileUtil.findJavaVersion() < 11 || SingleSourceFileUtil.hasClassSibling(fileObject);

            JavaPlatform jdk = readRunJdkFromAttribute(fileObject);

            if (compile) {
                Process p = SingleSourceFileUtil.compileJavaSource(fileObject, jdk);
                if (p.waitFor() != 0) {
                    return p;
                }
            }

            List<String> commandsList = new ArrayList<>();

            FileObject java = jdk.findTool("java"); //NOI18N
            File javaFile = FileUtil.toFile(java);
            String javaPath = javaFile.getAbsolutePath();
            URI cwd = SingleSourceFileUtil.getOptionsFor(fileObject).getWorkDirectory();
            File workDir = Utilities.toFile(cwd);

            ExplicitProcessParameters paramsFromAttributes =
                    ExplicitProcessParameters.builder()
                                             .args(readArgumentsFromAttribute(fileObject, SingleSourceFileUtil.FILE_ARGUMENTS))
                                             .launcherArgs(readArgumentsFromAttribute(fileObject, SingleSourceFileUtil.FILE_VM_OPTIONS))
                                             .launcherArgs(Arrays.asList(BaseUtilities.parseParameters(
                                                    NbPreferences.forModule(JavaPlatformManager.class).get(SingleSourceFileUtil.GLOBAL_VM_OPTIONS, "").trim()))) //NOI18N
                                             .workingDirectory(workDir)
                                             .build();

            ExplicitProcessParameters realParameters =
                    ExplicitProcessParameters.builder()
                                             .combine(params)
                                             .combine(paramsFromAttributes)
                                             .build();
            commandsList.add(javaPath);

            if (realParameters.getLauncherArguments()!= null) {
                commandsList.addAll(realParameters.getLauncherArguments());
            }

            if (port != null) {
                commandsList.add("-agentlib:jdwp=transport=dt_socket,address=" + port + ",server=n"); //NOI18N
            }

            if (compile) {
                commandsList.add("-cp");
                commandsList.add(FileUtil.toFile(fileObject.getParent()).toString());
                commandsList.add(fileObject.getName());
            } else {
                commandsList.add(FileUtil.toFile(fileObject).getAbsolutePath());
            }

            if (realParameters.getArguments() != null) {
                commandsList.addAll(realParameters.getArguments());
            }

            ProcessBuilder runFileProcessBuilder = new ProcessBuilder(commandsList);
            runFileProcessBuilder.environment().putAll(realParameters.getEnvironmentVariables());
            runFileProcessBuilder.directory(realParameters.getWorkingDirectory());
            runFileProcessBuilder.redirectErrorStream(true);
            runFileProcessBuilder.redirectOutput();

            return runFileProcessBuilder.start();
        } catch (IOException ex) {
            SingleSourceFileUtil.LOG.log(
                    Level.WARNING,
                    "Could not get InputStream of Run Process"); //NOI18N
        }
        return null;
    }

    private static JavaPlatform readRunJdkFromAttribute(FileObject fo) {
        String runJDKAttribute = fo.getAttribute(SingleSourceFileUtil.FILE_JDK) instanceof String str ? str : null;
        if (runJDKAttribute != null && !runJDKAttribute.isBlank()) {
            for (JavaPlatform jdk : JavaPlatformManager.getDefault().getInstalledPlatforms()) {
                if (runJDKAttribute.equals(jdk.getDisplayName())) {
                    return jdk;
                }
            }
            Logger.getLogger(LaunchProcess.class.getName()).log(Level.WARNING, "Unknown JDK: [{0}]", runJDKAttribute);
        }
        return JavaPlatformManager.getDefault().getDefaultPlatform();
    }

    private static List<String> readArgumentsFromAttribute(FileObject fileObject, String attributeName) {
        Object argumentsObject = fileObject.getAttribute(attributeName);
        if (!(argumentsObject instanceof String)) {
            return null;
        }
        return Arrays.asList(BaseUtilities.parseParameters(((String) argumentsObject).trim()));
    }
}
