package com.example.provisioner;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "provisioner")
public class ProvisionerProperties {

    private List<String> services = new ArrayList<>();
    private Map<String, String> tables = new HashMap<>();

    public List<String> getServices() {
        return services;
    }

    public void setServices(List<String> services) {
        this.services = services;
    }

    public Map<String, String> getTables() {
        return tables;
    }

    public void setTables(Map<String, String> tables) {
        this.tables = tables;
    }
}
