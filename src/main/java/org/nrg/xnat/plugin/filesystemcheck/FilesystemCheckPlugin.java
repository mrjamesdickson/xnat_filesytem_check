package org.nrg.xnat.plugin.filesystemcheck;

import lombok.extern.slf4j.Slf4j;
import org.nrg.framework.annotations.XnatDataModel;
import org.nrg.framework.annotations.XnatPlugin;
import org.nrg.xnat.plugin.filesystemcheck.rest.FilesystemCheckRestApi;
import org.nrg.xnat.plugin.filesystemcheck.services.FilesystemCheckService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

@XnatPlugin(
        value = "filesystemCheckPlugin",
        name = "XNAT Filesystem Check Plugin",
        description = "Validates XNAT filesystem integrity",
        entityPackages = "org.nrg.xnat.plugin.filesystemcheck"
)
@ComponentScan({
        "org.nrg.xnat.plugin.filesystemcheck.rest",
        "org.nrg.xnat.plugin.filesystemcheck.services"
})
@Slf4j
public class FilesystemCheckPlugin {

    public FilesystemCheckPlugin() {
        log.info("Initializing XNAT Filesystem Check Plugin");
    }

    @Bean
    public String filesystemCheckPluginVersion() {
        return "1.0.0-SNAPSHOT";
    }
}
