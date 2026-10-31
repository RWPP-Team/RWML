/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.io

import java.io.File
import java.util.zip.ZipFile
import kotlin.collections.iterator

fun File.unzipTo(targetFile: File, from: String = "") {
    val zipFile = ZipFile(this)
    for (entry in zipFile.entries()) {
        if (entry.name.startsWith(from)) {
            if (entry.isDirectory) {
                File(targetFile, entry.name).mkdirs()
            } else {
                val file = File(targetFile, entry.name)
                if (!file.exists()) {
                    if (!file.parentFile.exists()) file.parentFile.mkdirs()
                    file.createNewFile()
                }
                file.writeBytes(zipFile.getInputStream(entry).readBytes())
            }
        }
    }
}