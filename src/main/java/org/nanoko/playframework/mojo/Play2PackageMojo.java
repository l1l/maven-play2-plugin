/*
 * Copyright 2013 OW2 Nanoko Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.nanoko.playframework.mojo;

import net.lingala.zip4j.core.ZipFile;
import net.lingala.zip4j.exception.ZipException;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.util.Zip4jConstants;
import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.ExecuteWatchdog;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.AbstractFileFilter;
import org.apache.commons.io.filefilter.PrefixFileFilter;
import org.apache.commons.lang.StringUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Package the Play application.
 * The application is packaged as a Jar file. It is also possible to create the distribution package (zip).
 *
 * @goal package
 * @phase package
 */
public class Play2PackageMojo
        extends AbstractPlay2Mojo {

    /**
     * Output file classifier.
     *
     * @parameter default-value=""
     */
    String classifier;
    /**
     * Enables or disables the packaging of the whole distribution. The distribution is an autonomous archive containing
     * all the files required to run the play application. Play 2 module can disable the distribution packaging.
     *
     * @parameter default-value=true
     */
    boolean buildDist;
    /**
     * Enables or disables the attachment of the distribution file as an artifact to this project.
     * This option has no impact if the distribution is not built.
     *
     * @parameter default-value=true
     */
    boolean attachDist;
    /**
     * Enables or disables the deletion of the <tt>dist</tt> folder after having packaged the application and copied the
     * distribution file to <tt>target</tt>. It allows keeping the base directory cleaner.
     *
     * @parameter default-value=true
     */
    boolean deleteDist;
    /**
     * Allows customization of the play packaging. The files specified in this attribute will get added to the distribution
     * zip file. This allows, for example, to write your own start script and have it packaged in the distribution.
     * This is done post-packaging by the play framework.
     *
     * @parameter
     */
    List<String> additionalFiles = new ArrayList<String>();
    /**
     * Enables or disables the attachment of the -javadoc artifact.
     * This artifact is created during the play distribution packaging process.
     * If set to true, and if the artifact is not found, the build fails.
     *
     * @parameter default-value=false
     */
    boolean attachJavadoc;
    /**
     * Enables or disables the attachment of the -source artifact.
     * This artifact is created during the play distribution packaging process.
     * If set to true, and if the artifact is not found, the build fails.
     *
     * @parameter default-value=false
     */
    boolean attachSources;

    public void execute()
            throws MojoExecutionException {

        // Package
        packageApplication();
        File packagedApplication = moveApplicationPackageToTarget();

        // Distribution
        File dist = null;
        if (buildDist) {
            packageDistribution();
            dist = moveDistributionArtifactToTarget();

            // The javadoc and source files are created during the distribution construction.
            moveJavadocAndSourcesArtifactsToTarget();

            if (!additionalFiles.isEmpty()) {
                packageAdditionalFiles(additionalFiles, dist);
            }
        }
        attachArtifactsToProject(packagedApplication, dist);
    }

    private void packageAdditionalFiles(List<String> additionalFiles, File distributionFile) throws MojoExecutionException {
        try {
            ZipFile zipFile = new ZipFile(distributionFile);
            //create parameters object.
            ZipParameters parameters = new ZipParameters();
            parameters.setCompressionLevel(Zip4jConstants.DEFLATE_LEVEL_FASTEST);
            parameters.setIncludeRootFolder(true);
            // Let's safely assume that the zip filename is also the root directory all files are packaged in
            parameters.setRootFolderInZip(StringUtils.substringBeforeLast(distributionFile.getName(), ".zip"));
            parameters.setReadHiddenFiles(true);

            ArrayList<File> filesToAdd = new ArrayList<File>(additionalFiles.size());
            for (String file : additionalFiles) {
                File fileToAdd = new File(file);
                if (!fileToAdd.exists()) {
                    throw new MojoExecutionException(fileToAdd.getCanonicalPath() + " not found, can't add to package");
                }
                String message = String.format("Adding file to distribution zip [%s]: \n\t%s",
                        distributionFile.getCanonicalPath(), file);
                getLog().info(message);
                if(fileToAdd.isDirectory()){ // add as a directory
                    zipFile.addFolder(file, parameters);
                } else { //is a file.
                    filesToAdd.add(fileToAdd);
                }
            }
            //add non directory files.
            if(filesToAdd.size()>0){
                zipFile.addFiles(filesToAdd, parameters);
            }
        } catch (ZipException e) {
            throw new MojoExecutionException("Cannot add files to zipfile: " + distributionFile, e);
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot add files to zipfile: " + distributionFile, e);
        }
    }

    private File moveApplicationPackageToTarget() throws MojoExecutionException {
        File target = getBuildDirectory();
        File[] files = FileUtils.convertFileCollectionToFileArray(
                FileUtils.listFiles(target, new PackageFileFilter(), new PrefixFileFilter("scala-")));

        File mainJar = null;
        for (File file : files) {
            // We need to distinguish which file is the main artifacts.
            // We filter out -sources and -javadoc files.
            if (! file.getName().endsWith("-javadoc.jar")
                    && ! file.getName().endsWith("-sources.jar")) {
                mainJar = file;
            }
        }

        if (mainJar == null) {
            throw new MojoExecutionException("Cannot find packaged file");
        }

        try {
            if (StringUtils.isBlank(classifier)) {
                File out = new File(target, project.getBuild().getFinalName() + ".jar");
                getLog().info("Copying " + files[0].getName() + " to " + out.getName());
                FileUtils.copyFile(mainJar, out);
                return out;
            } else {
                File out = new File(target, project.getBuild().getFinalName() + "-" + classifier + ".jar");
                getLog().info("Copying " + files[0].getName() + " to " + out.getName());
                FileUtils.copyFile(mainJar, out);
                return out;
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot copy package file " + files[0].getAbsolutePath()
                    + " to " + target.getAbsolutePath());
        }
    }

    private void packageApplication() throws MojoExecutionException {
        String line = getPlay2().getAbsolutePath();

        CommandLine cmdLine = CommandLine.parse(line);
        cmdLine.addArgument("package");
        DefaultExecutor executor = new DefaultExecutor();

        if (timeout > 0) {
            ExecuteWatchdog watchdog = new ExecuteWatchdog(timeout);
            executor.setWatchdog(watchdog);
        }

        executor.setWorkingDirectory(project.getBasedir());
        executor.setExitValue(0);
        try {
            executor.execute(cmdLine, getEnvironment());
        } catch (IOException e) {
            throw new MojoExecutionException("Error during packaging", e);
        }
    }

    private void packageDistribution() throws MojoExecutionException {
        String line = getPlay2().getAbsolutePath();

        CommandLine cmdLine = CommandLine.parse(line);
        cmdLine.addArgument("dist");
        DefaultExecutor executor = new DefaultExecutor();

        if (timeout > 0) {
            ExecuteWatchdog watchdog = new ExecuteWatchdog(timeout);
            executor.setWatchdog(watchdog);
        }

        executor.setWorkingDirectory(project.getBasedir());
        executor.setExitValue(0);
        try {
            executor.execute(cmdLine, getEnvironment());
        } catch (IOException e) {
            throw new MojoExecutionException("Error during distribution creation", e);
        }
    }

    private File moveDistributionArtifactToTarget() throws MojoExecutionException {
        // The artifact is in dist, this is no more true with play 2.1 as the distribution directory can be customized.
        // we make the assumption that if dist does not exist, we try in target/dist.
        File dist = new File(project.getBasedir(), "dist");
        if (!dist.exists()) {
            getLog().info("The dist directory does not exist, lookup for the distribution file in target/dist");
            dist = new File(getBuildDirectory(), "dist");
            if (!dist.isDirectory()) {
                throw new MojoExecutionException("Cannot find the 'dist' directory, " +
                        "neither 'dist' nor 'target/dist' exist");
            }
        }
        Collection<File> found = FileUtils.listFiles(dist, new String[]{"zip"}, false);
        if (found.size() == 0) {
            throw new MojoExecutionException("The distribution file was not found in " + dist.getAbsolutePath());
        } else if (found.size() > 1) {
            throw new MojoExecutionException("Too many match for the distribution file in " + dist.getAbsolutePath());
        }

        // 1 file
        File file = found.toArray(new File[0])[0];

        getLog().info("Distribution file found : " + file.getAbsolutePath());

        File target = getBuildDirectory();
        File out = null;
        if (classifier == null) {
            out = new File(target, project.getBuild().getFinalName() + ".zip");
        } else {
            out = new File(target, project.getBuild().getFinalName() + "-" + classifier + ".zip");
        }

        try {
            getLog().info("Copying " + file.getName() + " to " + out.getName());
            FileUtils.copyFile(file, out, true);
        } catch (IOException e) {
            throw new MojoExecutionException("Can't copy the distribution file to the target folder", e);
        }

        // Delete the dist folder if enabled.
        if (deleteDist) {
            getLog().debug("Deleting " + dist.getAbsolutePath());
            FileUtils.deleteQuietly(dist);
        }

        return out;
    }

    private void moveJavadocAndSourcesArtifactsToTarget() throws MojoExecutionException {
        File sourceJar = null;
        File javadocJar = null;

        File target = getBuildDirectory();
        File[] files = FileUtils.convertFileCollectionToFileArray(
                FileUtils.listFiles(target, new PackageFileFilter(), new PrefixFileFilter("scala-")));

        for (File file : files) {
            // We need to select the right file.
            if (file.getName().endsWith("-sources.jar")) {
                sourceJar = file;
            } else if (file.getName().endsWith("-javadoc.jar")) {
                javadocJar = file;
            }
        }
        try {
            if (sourceJar != null) {
                getLog().info("Artifact containing sources found - copying to target");
                File out = new File(target, project.getBuild().getFinalName() + "-sources.jar");
                FileUtils.copyFile(sourceJar, out);
            }

            if (javadocJar != null) {
                getLog().info("Artifact containing javadoc found - copying to target");
                File out = new File(target, project.getBuild().getFinalName() + "-javadoc.jar");
                FileUtils.copyFile(javadocJar, out);
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Can't copy the javadoc and sources file to the target folder", e);
        }
    }

    private void attachArtifactsToProject(File app, File dist) throws MojoExecutionException {
        Artifact artifact = project.getArtifact();

        if (StringUtils.isBlank(classifier)) {
            artifact.setFile(app);
        } else {
            projectHelper.attachArtifact(project, "jar", classifier, app);
        }

        if (buildDist && attachDist) {
            projectHelper.attachArtifact(project, "zip", classifier, dist);
        }

        if (attachJavadoc) {
            File javadoc = new File(getBuildDirectory(), project.getBuild().getFinalName() + "-javadoc.jar");
            if (! javadoc.isFile()) {
                throw new MojoExecutionException("Javadoc attachment enabled, but the artifact is not found");
            }
            projectHelper.attachArtifact(project, "jar", "javadoc", javadoc);
        }

        if (attachSources) {
            File sources = new File(getBuildDirectory(), project.getBuild().getFinalName() + "-sources.jar");
            if (! sources.isFile()) {
                throw new MojoExecutionException("Sources attachment enabled, but the artifact is not found");
            }
            projectHelper.attachArtifact(project, "jar", "sources", sources);
        }

    }

    private class PackageFileFilter extends AbstractFileFilter {

        public boolean accept(File dir, String name) {
            // Must be in the scala dir
            return dir.getName().startsWith("scala")
                    // Must ends with Jar
                    && name.endsWith(".jar");
        }


    }
}
