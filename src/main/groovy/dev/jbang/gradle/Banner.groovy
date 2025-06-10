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

import groovy.transform.CompileStatic
import org.gradle.api.Project
import org.gradle.api.initialization.Settings
import org.gradle.api.invocation.Gradle
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

import java.text.MessageFormat

/**
 *
 * @author Andres Almiray
 * @since 0.1.0
 */
@CompileStatic
abstract class Banner implements BuildService<Params> {
    private static final String ORG_KORDAMP_BANNER = 'org.kordamp.banner'

    private String productVersion
    private String productId
    private final List<String> projectNames = []

    interface Params extends BuildServiceParameters {
    }

    void display(Settings settings) {
        if (checkIfVisited(settings.rootProject.name)) return
        checkMarkerFile(settings.gradle)
    }

    void display(Project project) {
        if (checkIfVisited(project.rootProject.name)) return
        checkMarkerFile(project.gradle)
    }

    private checkMarkerFile(Gradle gradle) {
        ResourceBundle bundle = ResourceBundle.getBundle(Banner.name)
        productVersion = bundle.getString('product.version')
        productId = bundle.getString('product.id')
        String productName = bundle.getString('product.name')
        String banner = MessageFormat.format(bundle.getString('product.banner'), productName, productVersion)

        boolean printBanner = null == System.getProperty(ORG_KORDAMP_BANNER) || Boolean.getBoolean(ORG_KORDAMP_BANNER)

        File parent = new File(gradle.gradleUserHomeDir, 'caches')
        File markerFile = getMarkerFile(parent)
        if (!markerFile.exists()) {
            markerFile.parentFile.mkdirs()
            markerFile.text = '1'
            if (printBanner) System.err.println(banner)
        } else {
            try {
                int count = Integer.parseInt(markerFile.text)
                if (count < 3) {
                    if (printBanner) System.err.println(banner)
                }
                markerFile.text = (count + 1) + ''
            } catch (NumberFormatException e) {
                markerFile.text = '1'
                if (printBanner) System.err.println(banner)
            }
        }
    }

    private boolean checkIfVisited(String name) {
        if (projectNames.contains(name)) {
            return true
        }
        projectNames.add(name)
        return false
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