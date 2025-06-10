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
package dev.jbang.gradle

import dev.jbang.gradle.tasks.JBangTask
import groovy.transform.CompileStatic
import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.logging.LogLevel
import org.gradle.api.plugins.BasePlugin

/**
 * Register a task named {@code jbang}.
 *
 * @author Andres Almiray
 */
@CompileStatic
class JBangPlugin implements Plugin<Project> {
    void apply(Project project) {
        if (project.gradle.startParameter.logLevel != LogLevel.QUIET) {
            project.gradle.sharedServices
                .registerIfAbsent('jbang-banner', Banner, { spec -> })
                .get().display(project)
        }

        project.plugins.apply(BasePlugin)

        project.tasks.register('jbang', JBangTask,
            new Action<JBangTask>() {
                @Override
                void execute(JBangTask t) {
                    t.group = 'Other'
                    t.description = 'Run JBang with the specified parameters.'
                }
            })
    }
}
