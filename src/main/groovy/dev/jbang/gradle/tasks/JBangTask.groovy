/*
 * SPDX-License-Identifier: MIT
 *
 * Copyright (c) 2020-2025 Andres Almiray.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package dev.jbang.gradle.tasks

import groovy.transform.CompileStatic
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.utils.IOUtils
import org.gradle.api.DefaultTask
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import org.gradle.tooling.BuildException
import org.gradle.wrapper.Download
import org.gradle.wrapper.IDownload
import org.gradle.wrapper.Logger
import org.kordamp.gradle.property.DirectoryState
import org.kordamp.gradle.property.ListState
import org.kordamp.gradle.property.SimpleDirectoryState
import org.kordamp.gradle.property.SimpleListState
import org.kordamp.gradle.property.SimpleStringState
import org.kordamp.gradle.property.StringState
import org.zeroturnaround.exec.ProcessExecutor
import org.zeroturnaround.exec.ProcessResult

import java.nio.file.Files
import java.nio.file.Path

import static org.kordamp.gradle.util.StringUtils.isBlank

/**
 * Run JBang with the specified parameters.
 *
 * @author Andres Almiray
 */
@CompileStatic
class JBangTask extends DefaultTask {
    private static final boolean IS_OS_WINDOWS = System.getProperty('os.name')
        .toLowerCase(Locale.ENGLISH)
        .contains('windows')

    private static final int OK_EXIT_CODE = 0

    private final StringState script
    private final StringState version
    private final ListState args
    private final ListState trusts
    private final DirectoryState installDir

    JBangTask() {
        DirectoryProperty jbangCacheDirectory = project.objects.directoryProperty()
        jbangCacheDirectory.set(new File(project.gradle.gradleUserHomeDir, 'caches/jbang'))

        script = SimpleStringState.of(this, 'jbang.script', '')
        version = SimpleStringState.of(this, 'jbang.version', 'latest')
        args = SimpleListState.of(this, 'jbang.args', [])
        trusts = SimpleListState.of(this, 'jbang.trusts', [])
        installDir = SimpleDirectoryState.of(this, 'jbang.install.dir', jbangCacheDirectory.get())
    }

    @Option(option = 'jbang-script', description = 'The script to be executed by JBang (REQUIRED).')
    void setScript(String script) {
        getScript().set(script)
    }

    @Option(option = 'jbang-version', description = 'The JBang version to be installed if missing (OPTIONAL).')
    void setVersion(String version) {
        getVersion().set(version)
    }

    @Option(option = 'jbang-args', description = 'The arguments to be used in the JBang script (if any) (OPTIONAL).')
    void setArgs(String args) {
        if (args) getArgs().set(args.split(',').toList())
    }

    @Option(option = 'jbang-trusts', description = 'URLs to be trusted (OPTIONAL).')
    void setTrusts(String trusts) {
        if (trusts) getTrusts().set(trusts.split(',').toList())
    }

    // -- Write properties --

    @Internal
    Property<String> getScript() {
        script.property
    }

    @Internal
    Property<String> getVersion() {
        version.property
    }

    @Internal
    ListProperty<String> getArgs() {
        args.property
    }

    @Internal
    ListProperty<String> getTrusts() {
        trusts.property
    }

    @Internal
    DirectoryProperty getInstallDir() {
        installDir.property
    }

    // -- Read-only properties --

    @Input
    Provider<String> getResolvedScript() {
        script.provider
    }

    @Input
    @Optional
    Provider<String> getResolvedVersion() {
        version.provider
    }

    @Input
    @Optional
    Provider<List<String>> getResolvedArgs() {
        args.provider
    }

    @Input
    @Optional
    Provider<List<String>> getResolvedTrusts() {
        trusts.provider
    }

    @Internal
    Provider<Directory> getResolvedInstallDir() {
        installDir.provider
    }

    // -- execution --

    @TaskAction
    void runTask() {
        if (isBlank(getResolvedScript().getOrNull())) {
            throw new IllegalArgumentException("A value for script must be defined")
        }

        detectJBang()
        executeTrust()
        executeJBang()
    }

    // -- copied from jbang-maven-plugin --

    private Path jbangHome

    private void detectJBang() {
        ProcessResult result = version()
        if (result.getExitValue() == OK_EXIT_CODE) {
            logger.info('Found JBang v.' + result.outputString())
        } else {
            String jbangVersion = getResolvedVersion().get()
            logger.warn('JBang not found. Checking cached version ' + jbangVersion)

            if ('latest' == jbangVersion) {
                jbangVersion = resolveLatestVersion()
            }

            Path jbangInstallPath = getResolvedInstallDir().get().getAsFile().toPath()
            Path installDir = jbangInstallPath.toAbsolutePath()
            jbangHome = installDir.resolve("jbang-${jbangVersion}".toString())

            result = version()
            if (result.getExitValue() == OK_EXIT_CODE) {
                logger.info('Found JBang v.' + result.outputString())
            } else {
                logger.warn('JBang not found. Downloading version ' + jbangVersion)
                download(jbangVersion)
                result = version()
                if (result.getExitValue() == OK_EXIT_CODE) {
                    logger.info('Using JBang v.' + result.outputString())
                }
            }
        }
    }

    private void download(String jbangVersion) {
        Path jbangInstallPath = getResolvedInstallDir().get().getAsFile().toPath()
        Path installDir = jbangInstallPath.toAbsolutePath()
        String uri = String.format('https://github.com/jbangdev/jbang/releases/download/v%s/jbang-%s.zip', jbangVersion, jbangVersion)

        Logger logger = new Logger(false)
        IDownload download = new Download(logger, 'jbang', jbangVersion)
        File localZipFile = jbangInstallPath.resolve("jbang-${jbangVersion}.zip".toString()).toFile()
        File tmpZipFile = new File(localZipFile.getParentFile(), localZipFile.getName() + '.part')
        tmpZipFile.delete()
        logger.log('Downloading ' + uri)
        download.download(uri.toURI(), tmpZipFile)
        tmpZipFile.renameTo(localZipFile)

        try {
            unzip(localZipFile, installDir)
        } catch (IOException e) {
            logger.log('Could not unzip ' + localZipFile.getAbsolutePath() + ' to ' + installDir + '.');
            logger.log('Reason: ' + e.getMessage())
            throw e
        }

        jbangHome = installDir.resolve("jbang-${jbangVersion}".toString())

        if (!IS_OS_WINDOWS) {
            jbangHome.resolve('bin').resolve('jbang').toFile().setExecutable(true, false)
        }
    }

    private String resolveLatestVersion() {
        File localVersionsFile = Files.createTempFile('jbang', 'versions.txt').toFile()
        String uri = 'https://www.jbang.dev/releases/latest/download/version.txt'

        Logger logger = new Logger(false)
        IDownload download = new Download(logger, 'jbang', 'latest')
        logger.log('Downloading ' + uri)
        download.download(uri.toURI(), localVersionsFile)
        localVersionsFile.text.trim()
    }

    private ProcessResult version() throws BuildException {
        List<String> command = command()
        command.add(findJBangExecutable() + ' version')
        try {
            return new ProcessExecutor()
                .command(command)
                .readOutput(true)
                .destroyOnExit()
                .execute()
        } catch (Exception e) {
            throw new BuildException('Error while fetching the JBang version', e)
        }
    }

    private void executeTrust() {
        if (!getResolvedTrusts().get()) {
            // No trust required
            return
        }
        List<String> command = command()
        command.add(findJBangExecutable() + ' trust add ' + String.join(' ', getResolvedTrusts().get()))
        ProcessResult result = execute(command)
        int exitValue = result.getExitValue()
        if (exitValue != 0 && exitValue != 1) {
            throw new IllegalStateException('Error while trusting JBang URLs. Exit code: ' + result.getExitValue())
        }
    }

    private void executeJBang() {
        List<String> command = command()
        StringBuilder executable = new StringBuilder(findJBangExecutable())
        executable.append(' run ').append(getResolvedScript().get())
        if (getResolvedArgs().get()) {
            executable.append(' ').append(String.join(' ', getResolvedArgs().get()))
        }
        command.add(executable.toString())
        ProcessResult result = execute(command)
        if (result.getExitValue() != 0) {
            throw new IllegalStateException('Error while executing JBang. Exit code: ' + result.getExitValue())
        }
    }

    private ProcessResult execute(List<String> command) throws BuildException {
        logger.info "jbang command = $command"
        try {
            return new ProcessExecutor()
                .command(command)
                .redirectOutput(System.out)
                .redirectError(System.err)
                .destroyOnExit()
                .execute()
        } catch (Exception e) {
            throw new BuildException("Error while executing JBang", e)
        }
    }

    private List<String> command() {
        List<String> command = new ArrayList<>()
        if (IS_OS_WINDOWS) {
            command.add('cmd.exe')
            command.add('/c')
        } else {
            command.add('sh')
            command.add('-c')
        }
        return command
    }

    private String findJBangExecutable() {
        if (jbangHome != null) {
            if (IS_OS_WINDOWS) {
                return jbangHome.resolve('bin/jbang.cmd').toString()
            } else {
                return jbangHome.resolve('bin/jbang').toString()
            }
        } else {
            if (IS_OS_WINDOWS) {
                return 'jbang.cmd'
            } else {
                return 'jbang'
            }
        }
    }

    private void unzip(File zipFile, Path installDir) throws IOException {
        ZipArchiveInputStream archive = null
        try {
            archive = new ZipArchiveInputStream(new BufferedInputStream(new FileInputStream(zipFile)))

            ZipArchiveEntry entry = null
            while ((entry = archive.nextZipEntry) != null) {
                if (entry.directory) continue
                File file = installDir.resolve(entry.name).toFile()
                file.parentFile.mkdirs()
                IOUtils.copy(archive, new FileOutputStream(file))
            }
        } catch (IOException e) {
            archive?.close()
            throw e
        }
    }
}