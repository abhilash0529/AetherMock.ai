package ai.aethermock.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "aethermock")
public class WireMockConfig {

    private WireMockProperties wiremock = new WireMockProperties();
    private String dataDir = "src/main/resources/mock-data";

    public static class WireMockProperties {
        private int port = 8089;

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }
    }

    public WireMockProperties getWiremock() {
        return wiremock;
    }

    public void setWiremock(WireMockProperties wiremock) {
        this.wiremock = wiremock;
    }

    public String getDataDir() {
        return dataDir;
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }
}

