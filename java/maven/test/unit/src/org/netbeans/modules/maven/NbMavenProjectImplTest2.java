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

package org.netbeans.modules.maven;

import java.io.File;
import java.io.IOException;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import static junit.framework.TestCase.assertEquals;
import static junit.framework.TestCase.assertTrue;
import org.netbeans.api.annotations.common.SuppressWarnings;
import org.netbeans.api.java.queries.SourceLevelQuery;
import org.netbeans.api.project.FileOwnerQuery;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.ProjectManager;
import org.netbeans.junit.NbTestCase;
import org.netbeans.junit.RandomlyFails;
import org.netbeans.modules.maven.api.NbMavenProject;
import org.netbeans.modules.maven.debug.MavenJPDAStart;
import org.netbeans.modules.maven.embedder.EmbedderFactory;
import org.netbeans.modules.maven.execute.MavenExecMonitor;
import org.netbeans.modules.maven.execute.MockMavenExec;
import org.netbeans.modules.maven.modelcache.MavenProjectCache;
import org.netbeans.modules.projectapi.nb.TimedWeakReference;
import org.netbeans.spi.project.ActionProgress;
import org.netbeans.spi.project.ActionProvider;
import org.netbeans.spi.project.LookupMerger;
import org.netbeans.spi.project.ProjectServiceProvider;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.filesystems.test.TestFileUtils;
import org.openide.modules.DummyInstalledFileLocator;
import org.openide.util.Lookup;
import org.openide.util.lookup.Lookups;
import org.openide.util.test.MockLookup;
import org.openide.windows.IOProvider;

public class NbMavenProjectImplTest2 extends NbTestCase {

    public NbMavenProjectImplTest2(String name) {
        super(name);
    }

    private FileObject wd;
    private File repo;
    private FileObject repoFO;
    private FileObject dataFO;

    private static File getTestNBDestDir() {
        String destDir = System.getProperty("test.netbeans.dest.dir");
        // set in project.properties as test-unit-sys-prop.test.netbeans.dest.dir
        assertNotNull("test.netbeans.dest.dir property has to be set when running within binary distribution", destDir);
        return new File(destDir);
    }

    protected @Override void setUp() throws Exception {
        // this property could be eventually initialized by NB module system, as MavenCacheDisabler i @OnStart, but that's unreliable.
        System.setProperty("maven.defaultProjectBuilder.disableGlobalModelCache", "true");
        
        clearWorkDir();

        wd = FileUtil.toFileObject(getWorkDir());
        //synchronous reload of maven project asserts sanoty in some tests..
        System.setProperty("test.reload.sync", "false");

        // This is needed, otherwose the core window's startup code will redirect
        // System.out/err to the IOProvider, and its Trivial implementation will redirect
        // it back to System.err - loop is formed. Initialize IOProvider first, it gets
        // the real System.err/out references.
        IOProvider p = IOProvider.getDefault();

        repo = EmbedderFactory.getProjectEmbedder().getLocalRepositoryFile();
        repoFO = FileUtil.toFileObject(repo);
        dataFO = FileUtil.toFileObject(getDataDir());
        
        // Configure the DummyFilesLocator with NB harness dir
        File destDirF = getTestNBDestDir();
        DummyInstalledFileLocator.registerDestDir(destDirF);
    }

    protected @Override Level logLevel() {
        return Level.FINE;
    }

    protected @Override String logRoot() {
        return "org.netbeans.modules.maven";
    }
    
   
    private void cleanMavenRepository() throws IOException {
        Path path = Paths.get(System.getProperty("user.home"), ".m2", "repository");
        if (!Files.isDirectory(path)) {
            return;
        }
        FileUtil.toFileObject(path.toFile()).delete();
    }
    
    /**
     * Primes the project including dependency fetch, waits for the operation to complete.
     * @throws Exception 
     */
    void primeProject(Project p) throws Exception {
        ActionProvider ap = p.getLookup().lookup(ActionProvider.class);
        if (ap == null) {
            throw new IllegalStateException("No action provider");
        }
        assertTrue(Arrays.asList(ap.getSupportedActions()).contains(ActionProvider.COMMAND_PRIME));
        
        CountDownLatch primeLatch = new CountDownLatch(1);
        ActionProgress prg = new ActionProgress() {
            @Override
            protected void started() {
            }

            @Override
            public void finished(boolean success) {
                primeLatch.countDown();
            }
        };
        ap.invokeAction(ActionProvider.COMMAND_PRIME, Lookups.fixed(prg));
        primeLatch.await(300, TimeUnit.SECONDS);
    }
    
    MavenExecMonitor mme;
    
    /**
     * Checks that subproject reload after the subproject primes.
     */
    public void testSubprojectsReloadAfterPriming() throws Exception {
        cleanMavenRepository();
        clearWorkDir();
        
        FileUtil.toFileObject(getWorkDir()).refresh();

        FileObject testApp = dataFO.getFileObject("projects/multiproject/democa");
        FileObject prjCopy = FileUtil.copyFile(testApp, FileUtil.toFileObject(getWorkDir()), "simpleProject");
        
        Project p = ProjectManager.getDefault().findProject(prjCopy);
        assertNotNull(p);

        Project sub = ProjectManager.getDefault().findProject(prjCopy.getFileObject("lib"));
        assertNotNull(sub);
        
        // check the project's validity:
        NbMavenProject subMaven = sub.getLookup().lookup(NbMavenProject.class);
        assertTrue("Fallback parent project is expected on unpopulated repository", NbMavenProject.isErrorPlaceholder(subMaven.getMavenProject()));
        assertTrue("Fallback subproject project is expected on unpopulated repository", NbMavenProject.isErrorPlaceholder(subMaven.getMavenProject()));
        
        primeProject(sub);
        assertFalse("Subproject must recover after priming itself", NbMavenProject.isIncomplete(subMaven.getMavenProject()));
    }
    
    /**
     * Checks that Priming action on a subproject actually runs on a reactor with --auto-make to build the subproject.
     * @throws Exception 
     */
    public void testSubprojectPrimeRunsReactor() throws Exception {
        cleanMavenRepository();
        clearWorkDir();
        
        mme = new MavenExecMonitor();
        MockLookup.setLayersAndInstances(mme);

        FileUtil.toFileObject(getWorkDir()).refresh();

        FileObject testApp = dataFO.getFileObject("projects/multiproject/democa");
        FileObject prjCopy = FileUtil.copyFile(testApp, FileUtil.toFileObject(getWorkDir()), "simpleProject");
        
        Project p = ProjectManager.getDefault().findProject(prjCopy);
        assertNotNull(p);

        Project sub = ProjectManager.getDefault().findProject(prjCopy.getFileObject("lib"));
        assertNotNull(sub);
        
        // check the project's validity:
        NbMavenProject subMaven = sub.getLookup().lookup(NbMavenProject.class);
        assertTrue("Fallback parent project is expected on unpopulated repository", NbMavenProject.isErrorPlaceholder(subMaven.getMavenProject()));
        assertTrue("Fallback subproject project is expected on unpopulated repository", NbMavenProject.isErrorPlaceholder(subMaven.getMavenProject()));
        
        primeProject(sub);
        
        assertEquals("Just single maven executed:", 1, mme.builders.size());
        
        ProcessBuilder b = mme.builders.getFirst();
        assertEquals("Runs in root project's dir", FileUtil.toFile(prjCopy),  b.directory());
        assertTrue("Specifies also-make", b.command().indexOf("--also-make") > 0);
        int idx = b.command().indexOf("--projects");
        assertTrue("Specifies projects", idx > 0);
        assertEquals("Runs up to the lib subprojectsd", "lib", b.command().get(idx + 1));
    }
    
    /**
     * Checks that subproject reload after its parent project primes.
     */
    public void testSubprojectsReloadAfterParentPriming() throws Exception {
        cleanMavenRepository();
        clearWorkDir();
        
        FileUtil.toFileObject(getWorkDir()).refresh();

        FileObject testApp = dataFO.getFileObject("projects/multiproject/democa");
        FileObject prjCopy = FileUtil.copyFile(testApp, FileUtil.toFileObject(getWorkDir()), "simpleProject");
        
        Project p = ProjectManager.getDefault().findProject(prjCopy);
        assertNotNull(p);

        Project sub = ProjectManager.getDefault().findProject(prjCopy.getFileObject("lib"));
        assertNotNull(sub);
        
        // check the project's validity:
        NbMavenProject parentMaven = p.getLookup().lookup(NbMavenProject.class);
        NbMavenProject subMaven = sub.getLookup().lookup(NbMavenProject.class);
        assertTrue("Fallback parent project is expected on unpopulated repository", NbMavenProject.isErrorPlaceholder(subMaven.getMavenProject()));
        assertTrue("Fallback subproject project is expected on unpopulated repository", NbMavenProject.isErrorPlaceholder(subMaven.getMavenProject()));
        
        primeProject(p);
        // subprojects are reloaded asynchronously. Watch out for child project's property for some time.
        CountDownLatch latch = new CountDownLatch(1);
        subMaven.addPropertyChangeListener((e) -> {
            if (NbMavenProject.PROP_PROJECT.equals(e.getPropertyName())) {
                latch.countDown();
            }
        });
        latch.await(10, TimeUnit.SECONDS);
        assertFalse("Subproject must recover after priming the parent", NbMavenProject.isIncomplete(subMaven.getMavenProject()));
    }
}
