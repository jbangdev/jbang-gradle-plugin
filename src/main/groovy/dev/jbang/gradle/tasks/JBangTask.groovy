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
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import org.gradle.tooling.BuildException
import org.gradle.wrapper.Download
import org.gradle.wrapper.IDownload
import org.gradle.wrapper.Logger
import org.zeroturnaround.exec.ProcessExecutor
import org.zeroturnaround.exec.ProcessResult

import javax.inject.Inject
import java.nio.file.Files
import java.nio.file.Path

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

    @Input
    final Property<String> script
    @Input
    @Optional
    final Property<String> version
    @Input
    @Optional
    final ListProperty<String> jbangArgs
    @Input
    @Optional
    final ListProperty<String> args
    @Input
    @Optional
    final ListProperty<String> trusts
    @InputDirectory
    @Optional
    final DirectoryProperty installDir

    @Inject
    JBangTask(ObjectFactory objects) {
        script = objects.property(String).convention('')
        version = objects.property(String).convention('latest')
        jbangArgs = objects.listProperty(String).convention([])
        args = objects.listProperty(String).convention([])
        trusts = objects.listProperty(String).convention([])
        installDir = objects.directoryProperty()

        String userHome = System.getProperty('user.home')
        installDir.convention(objects.directoryProperty().fileValue(new File(userHome, '.gradle' + File.separator + 'caches' + File.separator + 'jbang')))
    }

    @Option(option = 'jbang-script', description = 'The script to be executed by JBang (REQUIRED).')
    void setScript(String script) {
        getScript().set(script)
    }

    @Option(option = 'jbang-version', description = 'The JBang version to be installed if missing (OPTIONAL).')
    void setVersion(String version) {
        getVersion().set(version)
    }

    @Option(option = 'jbang-jbang-args', description = 'JBang arguments to be used by JBang when running the script (if any) (OPTIONAL).')
    void setJbangArgs(String jbangArgs) {
        if (jbangArgs) getJbangArgs().set(jbangArgs.split(',').toList())
    }

    @Option(option = 'jbang-args', description = 'The arguments to be used in the JBang script (if any) (OPTIONAL).')
    void setArgs(String args) {
        if (args) getArgs().set(args.split(',').toList())
    }

    @Option(option = 'jbang-trusts', description = 'URLs to be trusted (OPTIONAL).')
    void setTrusts(String trusts) {
        if (trusts) getTrusts().set(trusts.split(',').toList())
    }

    @TaskAction
    void runTask() {
        if (!script.getOrNull()) {
            throw new IllegalArgumentException("A value for script must be defined")
        }

        Files.createDirectories(installDir.get().asFile.toPath())

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
            String jbangVersion = version.get()
            logger.warn('JBang not found. Checking cached version ' + jbangVersion)

            if ('latest' == jbangVersion) {
                jbangVersion = resolveLatestVersion()
            }

            Path jbangInstallPath = installDir.get().getAsFile().toPath()
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
        Path jbangInstallPath = installDir.get().getAsFile().toPath()
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
        if (!trusts.get()) {
            // No trust required
            return
        }
        List<String> command = command()
        String trustCommand = findJBangExecutable() + ' trust add ' + String.join(' ', trusts.get())
        command.add(trustCommand)

        // Log the user-friendly trust command
        logger.lifecycle("Executing JBang trust command: ${trustCommand}")

        ProcessResult result = execute(command)
        int exitValue = result.getExitValue()
        if (exitValue != 0 && exitValue != 1) {
            throw new IllegalStateException('Error while trusting JBang URLs. Exit code: ' + result.getExitValue())
        }
    }

    private void executeJBang() {
        List<String> command = command()
        // A single string is needed, because if "sh -c jbang" is used for execution, the parameters need to be passed as single string
        StringBuilder executable = new StringBuilder(findJBangExecutable())
        executable.append(' run ')
        if (jbangArgs.get()) {
            executable.append(' ').append(String.join(' ', jbangArgs.get())).append(' ')
        }

        executable.append(script.get())
        if (args.get()) {
            executable.append(' ').append(String.join(' ', args.get()))
        }

        command.add(executable.toString())

        // Log the user-friendly JBang command
        logger.lifecycle("Executing JBang command: ${executable.toString()}")

        ProcessResult result = execute(command)
        if (result.getExitValue() != 0) {
            throw new IllegalStateException('Error while executing JBang. Exit code: ' + result.getExitValue())
        }
    }

    private ProcessResult execute(List<String> command) throws BuildException {
        logger.debug "Full command with shell wrapper: $command"
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
        try (ZipArchiveInputStream archive = new ZipArchiveInputStream(new BufferedInputStream(new FileInputStream(zipFile)))) {
            ZipArchiveEntry entry
            while ((entry = archive.nextZipEntry) != null) {
                if (entry.directory) continue
                File file = installDir.resolve(entry.name).toFile()
                file.parentFile.mkdirs()
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    IOUtils.copy(archive, fos)
                }
            }
        }
    }
}
