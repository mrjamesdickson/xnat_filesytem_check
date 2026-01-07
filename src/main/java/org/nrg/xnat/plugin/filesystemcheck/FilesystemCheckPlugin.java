/*
 * XNAT Filesystem Check Plugin
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 *
 * This software is distributed under the terms described in the LICENSE file.
 */
package org.nrg.xnat.plugin.filesystemcheck;

import lombok.extern.slf4j.Slf4j;
import org.nrg.framework.annotations.XnatPlugin;
import org.springframework.context.annotation.ComponentScan;

@XnatPlugin(
        value = "filesystemCheckPlugin",
        name = "XNAT Filesystem Check Plugin",
        description = "Validates XNAT filesystem integrity",
        entityPackages = "org.nrg.xnat.plugin.filesystemcheck.entities"
)
@ComponentScan({
        "org.nrg.xnat.plugin.filesystemcheck.rest",
        "org.nrg.xnat.plugin.filesystemcheck.services",
        "org.nrg.xnat.plugin.filesystemcheck.repositories",
        "org.nrg.xnat.plugin.filesystemcheck.config"
})
@Slf4j
public class FilesystemCheckPlugin {

    public FilesystemCheckPlugin() {
        log.info("Initializing XNAT Filesystem Check Plugin");
    }
}
