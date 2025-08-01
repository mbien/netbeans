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
package org.netbeans.modules.java.hints.errors;

import com.sun.source.util.TreePath;
import java.util.List;
import java.util.Set;
import org.netbeans.api.annotations.common.NonNull;
import org.netbeans.api.java.platform.JavaPlatform;
import org.netbeans.api.java.platform.JavaPlatformManager;
import org.netbeans.api.java.queries.SourceLevelQuery;
import org.netbeans.api.java.source.ClasspathInfo.PathKind;
import org.netbeans.api.java.source.CompilationInfo;
import org.netbeans.modules.java.hints.spi.ErrorRule;
import org.netbeans.spi.editor.hints.ChangeInfo;
import org.netbeans.spi.editor.hints.Fix;
import org.openide.filesystems.FileObject;
import org.openide.util.NbBundle;
import org.openide.util.Parameters;
import org.netbeans.modules.java.hints.spi.preview.PreviewEnabler;
import org.netbeans.modules.java.hints.spi.preview.PreviewEnabler.Factory;
import org.openide.modules.SpecificationVersion;
import org.openide.util.Lookup;

/**
 * Handle error rule "compiler.err.preview.feature.disabled.plural" and provide
 * the fix for Single Source Java File.
 *
 * @author Arunava Sinha
 */
public class EnablePreview implements ErrorRule<Void> {

    private static final Set<String> ERROR_CODES = Set.of(
            "compiler.err.preview.feature.disabled", //NOI18N
            "compiler.err.preview.feature.disabled.plural", // NOI18N
            "compiler.err.is.preview", // NOI18N
            // workaround: if a feature is no longer preview from the perspective of nb-javac,
            // it may surface as "not supported on source level" error while still requiering
            // preview on the project target JDK
            "compiler.err.feature.not.supported.in.source", // NOI18N
            "compiler.err.feature.not.supported.in.source.plural" // NOI18N
    );

    @Override
    public Set<String> getCodes() {
        return ERROR_CODES;
    }

    @Override
    @NonNull
    public List<Fix> run(CompilationInfo compilationInfo, String diagnosticKey, int offset, TreePath treePath, Data<Void> data) {
        final FileObject file = compilationInfo.getFileObject();

        if (file != null) {
            SpecificationVersion platformVersion = null;

            FileObject jlObject = compilationInfo.getClasspathInfo().getClassPath(PathKind.BOOT).findResource("java/lang/Object.class");
            for (JavaPlatform platform : JavaPlatformManager.getDefault().getInstalledPlatforms()) {
                if (jlObject == platform.getBootstrapLibraries().findResource("java/lang/Object.class")) {
                    platformVersion = platform.getSpecification().getVersion();
                    break;
                }
            }

            String sourceLevel = SourceLevelQuery.getSourceLevel(compilationInfo.getFileObject());

            if (sourceLevel == null) {
                return List.of();
            }

            if (sourceLevel.startsWith("1.")) {
                sourceLevel = sourceLevel.substring(2);
            }

            String newSourceLevel = null;
            
            if (platformVersion != null) {
                if (platformVersion.compareTo(new SpecificationVersion("12")) < 0) {
                    return List.of(); // preview concept was introduced with JDK 12
                }
                if (diagnosticKey.startsWith("compiler.err.feature.not.supported.in.source")
                        && platformVersion.compareTo(new SpecificationVersion(sourceLevel)) == 0
                        && compilationInfo.getPreview().isEnabled()) {
                    return List.of(); // nothing we can do
                }
                if (platformVersion.compareTo(new SpecificationVersion(sourceLevel)) > 0) {
                    newSourceLevel = platformVersion.toString();
                }
            }

            for (Factory factory : Lookup.getDefault().lookupAll(Factory.class)) {
                PreviewEnabler enabler = factory.enablerFor(file);
                if (enabler != null) {
                    return List.of(new ResolveFix(enabler, newSourceLevel));
                }
            }
        }

        return List.of();
    }

    @Override
    public String getId() {
        return EnablePreview.class.getName();
    }

    @Override
    public String getDisplayName() {
        return NbBundle.getMessage(EnablePreview.class, "FIX_EnablePreviewFeature"); // NOI18N
    }

    public String getDescription() {
        return NbBundle.getMessage(EnablePreview.class, "FIX_EnablePreviewFeature"); // NOI18N
    }

    @Override
    public void cancel() {
    }

    public static final class ResolveFix implements Fix {

        private final PreviewEnabler enabler;
        private final String newSourceLevel;
        private final boolean canChangeSourceLevel;

        public ResolveFix(@NonNull PreviewEnabler enabler, @NonNull String newSourceLevel) {
            Parameters.notNull("enabler", enabler); //NOI18N
            this.enabler = enabler;
            this.newSourceLevel = newSourceLevel;
            this.canChangeSourceLevel = newSourceLevel != null ? enabler.canChangeSourceLevel()
                                                               : true;
        }

        @Override //TODO: add "and set source level" if needed
        public String getText() {
            if (newSourceLevel != null) {
                if (canChangeSourceLevel) {
                    return NbBundle.getMessage(EnablePreview.class, "FIX_EnablePreviewFeatureSetSourceLevel", newSourceLevel);
                } else {
                    return NbBundle.getMessage(EnablePreview.class, "FIX_EnablePreviewFeatureSetSourceLevelManual", newSourceLevel);
                }
            } else {
                return NbBundle.getMessage(EnablePreview.class, "FIX_EnablePreviewFeature");  // NOI18N
            }
        }

        @Override
        public ChangeInfo implement() throws Exception {
            enabler.enablePreview(newSourceLevel);
            return null;
        }

        public String getNewSourceLevel() {
            return newSourceLevel;
        }

    }

}
