/*
 * SPDX-License-Identifier: MIT
 *
 * Copyright (c) 2020 Andres Almiray.
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

import groovy.transform.CompileStatic
import org.gradle.BuildAdapter
import org.gradle.BuildResult
import org.gradle.api.Project

import java.text.MessageFormat

/**
 *
 * @author Andres Almiray
 */
@CompileStatic
final class Banner {
    private final ResourceBundle bundle = ResourceBundle.getBundle(Banner.name)
    private final String productVersion = bundle.getString('product.version')
    private final String productId = bundle.getString('product.id')
    private final String productName = bundle.getString('product.name')
    private final String banner = MessageFormat.format(bundle.getString('product.banner'), productName, productVersion)
    private final List<String> visited = []

    private static final Banner b = new Banner()

    private Banner() {
        // nooop
    }

    static void display(Project project) {
        if (b.visited.contains(project.rootProject.name)) {
            return
        }
        b.visited.add(project.rootProject.name)
        project.gradle.addBuildListener(new BuildAdapter() {
            @Override
            void buildFinished(BuildResult result) {
                b.visited.clear()
            }
        })

        File parent = new File(project.gradle.gradleUserHomeDir, 'caches')
        File markerFile = b.getMarkerFile(parent)
        if (!markerFile.exists()) {
            markerFile.parentFile.mkdirs()
            markerFile.text = '1'
            println(b.banner)
        } else {
            try {
                int count = Integer.parseInt(markerFile.text)
                if (count < 3) {
                    println(b.banner)
                }
                markerFile.text = (count + 1) + ''
            } catch (NumberFormatException e) {
                markerFile.text = '1'
                println(b.banner)
            }
        }
    }

    private File getMarkerFile(File parent) {
        new File(parent,
            'kordamp' +
                File.separator +
                productId +
                File.separator +
                productVersion +
                File.separator +
                'marker.txt')
    }
}
