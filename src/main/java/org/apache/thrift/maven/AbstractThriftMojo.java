/*
* Licensed to the Apache Software Foundation (ASF) under one
* or more contributor license agreements. See the NOTICE file
* distributed with this work for additional information
* regarding copyright ownership. The ASF licenses this file
* to you under the Apache License, Version 2.0 (the
* "License"); you may not use this file except in compliance
* with the License. You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing,
* software distributed under the License is distributed on an
* "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
* KIND, either express or implied. See the License for the
* specific language governing permissions and limitations
* under the License.
*/
package org.apache.thrift.maven;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableSet;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.cli.CommandLineException;
import org.codehaus.plexus.util.io.RawInputStreamFacade;

import java.io.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static com.google.common.base.Preconditions.*;
import static com.google.common.collect.Sets.newHashSet;
import static java.lang.String.format;
import static java.util.Arrays.asList;
import static java.util.Collections.list;
import static org.codehaus.plexus.util.FileUtils.*;

/**
 * Abstract Mojo implementation.
 */
abstract class AbstractThriftMojo extends AbstractMojo {

    private static final String THRIFT_FILE_SUFFIX = ".thrift";
    private static final String DEFAULT_INCLUDES = "**/*" + THRIFT_FILE_SUFFIX;
    /**
     * The current Maven project.
     *
     * @parameter default-value="${project}"
     * @readonly
     * @required
     */
    protected MavenProject project;
    /**
     * A helper used to add resources to the project.
     *
     * @component
     * @required
     */
    protected MavenProjectHelper projectHelper;
    /**
     * This is the path to the {@code thrift} executable. By default it will search the {@code $PATH}.
     */
    private String thriftCompilerPath;
    /**
     * This string is passed to the {@code --gen} option of the {@code thrift} parameter. By default
     * it will generate Java output. The main reason for this option is to be able to add options
     * to the Java generator - if you generate something else, you're on your own.
     *
     * @parameter default-value="java"
     * @required
     */
    private String generator;
    /**
     * @parameter
     */
    private File[] additionalThriftPathElements = new File[]{};
    /**
     * Since {@code thrift} cannot access jars, thrift files in dependencies are extracted to this location
     * and deleted on exit. This directory is always cleaned during execution.
     *
     * @parameter
     * @required
     */
    private File workingDirectory;
    /**
     * The source directories containing the sources to be compiled.
     *
     * @parameter default-value="${basedir}/src/test/thrift"
     * @required
     */
    protected File thriftSourceRoot;
    /**
     * This is the path to the local maven {@code repository}.
     *
     * @parameter default-value="${localRepository}"
     * @required
     */
    private ArtifactRepository localRepository;
    /**
     * Set this to {@code false} to disable hashing of dependent jar paths.
     * <p/>
     * This plugin expands jars on the classpath looking for embedded .thrift files.
     * Normally these paths are hashed (MD5) to avoid issues with long file names on windows.
     * However if this property is set to {@code false} longer paths will be used.
     *
     * @parameter default-value="true"
     * @required
     */
    private boolean hashDependentPaths;
    /**
     * @parameter
     */
    private Set<String> includes = ImmutableSet.of(DEFAULT_INCLUDES);
    /**
     * @parameter
     */
    private Set<String> excludes = ImmutableSet.of();
    /**
     * @parameter
     */
    private long staleMillis = 0;
    /**
     * @parameter
     */
    private boolean checkStaleness = false;

    /**
     * Executes the mojo.
     */
    public void execute() throws MojoExecutionException, MojoFailureException {
        checkParameters();
        final File thriftSourceRoot = getThriftSourceRoot();
        if (thriftSourceRoot.exists()) {
            try {
                ImmutableSet<File> thriftFiles = findThriftFilesInDirectory(thriftSourceRoot);
                final File outputDirectory = getOutputDirectory();
                ImmutableSet<File> outputFiles = findGeneratedFilesInDirectory(getOutputDirectory());
                if (thriftFiles.isEmpty()) {
                    getLog().info("No thrift files to compile.");
                } else if (checkStaleness && ((lastModified(thriftFiles) + staleMillis) < lastModified(outputFiles))) {
                    getLog().info("Skipping compilation because target directory newer than sources.");
                    attachFiles();
                } else {
                    //ImmutableSet<File> derivedThriftPathElements = makeThriftPathFromJars(workingDirectory, getDependencyArtifactFiles());
                    importThriftPathFromJars();
                    outputDirectory.mkdirs();
                    extractCompilerToWorkingDirectory();
                    // Quick fix to fix issues with two mvn installs in a row (ie no clean)
                    // cleanDirectory(outputDirectory);

                    Thrift thrift = new Thrift.Builder(this.thriftCompilerPath, outputDirectory, getLog())
                            .setGenerator(generator)
                            .addThriftPathElement(thriftSourceRoot)
                            //.addThriftPathElements(derivedThriftPathElements)
                            .addThriftPathElements(asList(additionalThriftPathElements))
                            .addThriftFiles(thriftFiles)
                            .build();
                    final int exitStatus = thrift.compile();
                    if (exitStatus != 0) {
                        getLog().error("thrift failed output: " + thrift.getOutput());
                        getLog().error("thrift failed error: " + thrift.getError());

                        throw new MojoFailureException(
                                "thrift did not exit cleanly. Review output for more information.");
                    }
                    attachFiles();
                    // wait until thrift executable is really finished before deleting directory
                    try {
                        Thread.sleep(2000); // dirty hack, no better way to do this!
                    } catch (InterruptedException ignored) {
                    }
                    FileUtils.deleteDirectory(workingDirectory);
                }
            } catch (IOException e) {
                throw new MojoExecutionException("An IO error occured", e);
            } catch (IllegalArgumentException e) {
                throw new MojoFailureException("thrift failed to execute because: " + e.getMessage(), e);
            } catch (CommandLineException e) {
                throw new MojoExecutionException("An error occurred while invoking thrift.", e);
            }
        } else {
            getLog().info(format("%s does not exist. Review the configuration or consider disabling the plugin.", thriftSourceRoot));
        }
    }

    void extractCompilerToWorkingDirectory() throws IOException {
        ClassLoader pluginClassLoader = Thread.currentThread().getContextClassLoader();
        String detectedOsName = normalizeOs(System.getProperties().getProperty("os.name"));
        String detectedOsArch = normalizeArch(System.getProperties().getProperty("os.arch"));
        getLog().info("Detected operating system: " + detectedOsName + '-' + detectedOsArch);
        getLog().info("Working Directory = " + workingDirectory);
        workingDirectory.mkdirs();
        File thriftCompiler;
        switch (detectedOsName) {
            case "windows":
                thriftCompiler = new File(workingDirectory + "/thrift.exe");
                copyInputStreamToFile(pluginClassLoader.getResourceAsStream("thrift.exe"), thriftCompiler);
                thriftCompiler.setReadOnly();
                thriftCompiler.setExecutable(true);
                this.thriftCompilerPath = thriftCompiler.getPath();
                break;
            case "linux":
                thriftCompiler = new File(workingDirectory + "/thrift");
                copyInputStreamToFile(pluginClassLoader.getResourceAsStream("thrift"), thriftCompiler);
                thriftCompiler.setReadOnly();
                thriftCompiler.setExecutable(true);
                this.thriftCompilerPath = thriftCompiler.getPath();
                break;
            default:
                throw new IOException("No Thrift compiler found for " + detectedOsName + '-' + detectedOsArch + "!");
        }
        getLog().info("Thrift compiler path = " + this.thriftCompilerPath);
    }

    ImmutableSet<File> findGeneratedFilesInDirectory(File directory) throws IOException {
        if (directory == null || !directory.isDirectory())
            return ImmutableSet.of();

        List<File> javaFilesInDirectory = getFiles(directory, "**/*.java", null);
        return ImmutableSet.copyOf(javaFilesInDirectory);
    }

    private long lastModified(ImmutableSet<File> files) {
        long result = 0;
        for (File file : files) {
            if (file.lastModified() > result)
                result = file.lastModified();
        }
        return result;
    }

    private void checkParameters() {
        checkNotNull(project, "mavenProject");
        checkNotNull(projectHelper, "projectHelper");
        checkNotNull(generator, "generator");
        final File thriftSourceRoot = getThriftSourceRoot();
        checkNotNull(thriftSourceRoot);
        checkArgument(!thriftSourceRoot.isFile(), "thriftSourceRoot is a file, not a directory");
        checkNotNull(workingDirectory, "workingDirectory");
        checkState(!workingDirectory.isFile(), "workingDirectory is a file, not a directory");
        final File outputDirectory = getOutputDirectory();
        checkNotNull(outputDirectory);
        checkState(!outputDirectory.isFile(), "the outputDirectory is a file, not a directory");
    }

    protected abstract File getThriftSourceRoot();

    protected abstract Set<Artifact> getDependencyArtifacts();

    protected abstract File getOutputDirectory();

    protected abstract void attachFiles();

    /**
     * Gets the {@link File} for each dependency artifact.
     *
     * @return A set of all dependency artifacts.
     */
    private ImmutableSet<File> getDependencyArtifactFiles() {
        Set<File> dependencyArtifactFiles = newHashSet();
        for (Artifact artifact : getDependencyArtifacts()) {
            dependencyArtifactFiles.add(artifact.getFile());
        }
        return ImmutableSet.copyOf(dependencyArtifactFiles);
    }

    /**
     * @throws IOException
     */
    void importThriftPathFromJars() throws IOException, MojoExecutionException {
        for (Artifact artifact : getDependencyArtifacts()) {
            // for some reason under IAM, we receive poms as dependent files
            // I am excluding .xml rather than including .jar as there may be other extensions in use (sar, har, zip)
            if (artifact.getFile().isFile() && artifact.getFile().canRead() && !artifact.getFile().getName().endsWith(".xml")) {

                // create the jar file. the constructor validates.
                JarFile classpathJar;
                try {
                    classpathJar = new JarFile(artifact.getFile());
                } catch (IOException e) {
                    throw new IllegalArgumentException(format("%s was not a readable artifact", artifact.getFile()));
                }
                for (JarEntry jarEntry : list(classpathJar.entries())) {
                    if (jarEntry.getName().endsWith(THRIFT_FILE_SUFFIX)) {
                        //String idlFileName = jarEntry.getName().substring((jarEntry.getName().lastIndexOf("/") + 1));
                        final File uncompressedCopy =  new File(thriftSourceRoot, artifact.getArtifactId().replaceAll("[^a-zA-Z0-9]", "").toLowerCase() + ".thrift");
                        copyStreamToFile(new RawInputStreamFacade(classpathJar.getInputStream(jarEntry)), uncompressedCopy);
                    }
                }
            } else if (artifact.getFile().isDirectory()) {
                File[] thriftFiles = artifact.getFile().listFiles(new FilenameFilter() {
                    public boolean accept(File dir, String name) {
                        return name.endsWith(THRIFT_FILE_SUFFIX);
                    }
                });
            }
        }
    }

    /**
     * @throws IOException
     */
    ImmutableSet<File> makeThriftPathFromJars(File temporaryThriftFileDirectory, Iterable<File> classpathElementFiles)
            throws IOException, MojoExecutionException {
        checkNotNull(classpathElementFiles, "classpathElementFiles");
        // clean the temporary directory to ensure that stale files aren't used
        if (temporaryThriftFileDirectory.exists()) {
            cleanDirectory(temporaryThriftFileDirectory);
        }
        Set<File> thriftDirectories = newHashSet();
        for (File classpathElementFile : classpathElementFiles) {
            // for some reason under IAM, we receive poms as dependent files
            // I am excluding .xml rather than including .jar as there may be other extensions in use (sar, har, zip)
            if (classpathElementFile.isFile() && classpathElementFile.canRead() &&
                    !classpathElementFile.getName().endsWith(".xml")) {

                // create the jar file. the constructor validates.
                JarFile classpathJar;
                try {
                    classpathJar = new JarFile(classpathElementFile);
                } catch (IOException e) {
                    throw new IllegalArgumentException(format(
                            "%s was not a readable artifact", classpathElementFile));
                }
                for (JarEntry jarEntry : list(classpathJar.entries())) {
                    final String jarEntryName = jarEntry.getName();
                    if (jarEntry.getName().endsWith(THRIFT_FILE_SUFFIX)) {
                        final File uncompressedCopy =
                                new File(new File(temporaryThriftFileDirectory,
                                        truncatePath(classpathJar.getName())), jarEntryName);
                        uncompressedCopy.getParentFile().mkdirs();
                        copyStreamToFile(new RawInputStreamFacade(classpathJar
                                .getInputStream(jarEntry)), uncompressedCopy);
                        thriftDirectories.add(uncompressedCopy.getParentFile());
                    }
                }
            } else if (classpathElementFile.isDirectory()) {
                File[] thriftFiles = classpathElementFile.listFiles(new FilenameFilter() {
                    public boolean accept(File dir, String name) {
                        return name.endsWith(THRIFT_FILE_SUFFIX);
                    }
                });

                if (thriftFiles.length > 0) {
                    thriftDirectories.add(classpathElementFile);
                }
            }
        }
        return ImmutableSet.copyOf(thriftDirectories);
    }

    ImmutableSet<File> findThriftFilesInDirectory(File directory) throws IOException {
        checkNotNull(directory);
        checkArgument(directory.isDirectory(), "%s is not a directory", directory);
        List<File> thriftFilesInDirectory = getFiles(directory,
                Joiner.on(",").join(includes),
                Joiner.on(",").join(excludes));
        return ImmutableSet.copyOf(thriftFilesInDirectory);
    }

    ImmutableSet<File> findThriftFilesInDirectories(Iterable<File> directories) throws IOException {
        checkNotNull(directories);
        Set<File> thriftFiles = newHashSet();
        for (File directory : directories) {
            thriftFiles.addAll(findThriftFilesInDirectory(directory));
        }
        return ImmutableSet.copyOf(thriftFiles);
    }

    /**
     * Truncates the path of jar files so that they are relative to the local repository.
     *
     * @param jarPath the full path of a jar file.
     * @return the truncated path relative to the local repository or root of the drive.
     */
    String truncatePath(final String jarPath) throws MojoExecutionException {

        if (hashDependentPaths) {
            try {
                return toHexString(MessageDigest.getInstance("MD5").digest(jarPath.getBytes()));
            } catch (NoSuchAlgorithmException e) {
                throw new MojoExecutionException("Failed to expand dependent jar", e);
            }
        }

        String repository = localRepository.getBasedir().replace('\\', '/');
        if (!repository.endsWith("/")) {
            repository += "/";
        }

        String path = jarPath.replace('\\', '/');
        int repositoryIndex = path.indexOf(repository);
        if (repositoryIndex != -1) {
            path = path.substring(repositoryIndex + repository.length());
        }

        // By now the path should be good, but do a final check to fix windows machines.
        int colonIndex = path.indexOf(':');
        if (colonIndex != -1) {
            // 2 = :\ in C:\
            path = path.substring(colonIndex + 2);
        }

        return path;
    }

    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();

    public static String toHexString(byte[] byteArray) {
        final StringBuilder hexString = new StringBuilder(2 * byteArray.length);
        for (final byte b : byteArray) {
            hexString.append(HEX_CHARS[(b & 0xF0) >> 4]).append(HEX_CHARS[b & 0x0F]);
        }
        return hexString.toString();
    }

    private static String normalizeOs(String value) {
        value = normalize(value);
        if (value.startsWith("aix")) {
            return "aix";
        }
        if (value.startsWith("hpux")) {
            return "hpux";
        }
        if (value.startsWith("os400")) {
            // Avoid the names such as os4000
            if (value.length() <= 5 || !Character.isDigit(value.charAt(5))) {
                return "os400";
            }
        }
        if (value.startsWith("linux")) {
            return "linux";
        }
        if (value.startsWith("macosx") || value.startsWith("osx")) {
            return "osx";
        }
        if (value.startsWith("freebsd")) {
            return "freebsd";
        }
        if (value.startsWith("openbsd")) {
            return "openbsd";
        }
        if (value.startsWith("netbsd")) {
            return "netbsd";
        }
        if (value.startsWith("solaris") || value.startsWith("sunos")) {
            return "sunos";
        }
        if (value.startsWith("windows")) {
            return "windows";
        }
        return "unknown";
    }

    private static String normalizeArch(String value) {
        value = normalize(value);
        if (value.matches("^(x8664|amd64|ia32e|em64t|x64)$")) {
            return "x86_64";
        }
        if (value.matches("^(x8632|x86|i[3-6]86|ia32|x32)$")) {
            return "x86_32";
        }
        if (value.matches("^(ia64|itanium64)$")) {
            return "itanium_64";
        }
        if (value.matches("^(sparc|sparc32)$")) {
            return "sparc_32";
        }
        if (value.matches("^(sparcv9|sparc64)$")) {
            return "sparc_64";
        }
        if (value.matches("^(arm|arm32)$")) {
            return "arm_32";
        }
        if (value.equals("aarch64")) {
            return "aarch_64";
        }
        if (value.matches("^(ppc|ppc32)$")) {
            return "ppc_32";
        }
        if (value.equals("ppc64")) {
            return "ppc_64";
        }
        return "unkown";
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", "");
    }

    private void copyInputStreamToFile(InputStream in, File file) throws IOException {
        OutputStream out = null;
        try {
            out = new FileOutputStream(file);
            byte[] buf = new byte[32768];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
        } finally {
            if (out != null) {
                out.close();
            }
            in.close();
        }
    }
}