import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SpringBootApplication
public class ActiveMQJolokiaApplication {

    public static void main(String[] args) {
        SpringApplication.run(ActiveMQJolokiaApplication.class, args);
    }

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}

@Service
public class ActiveMQQueueService {
    
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String jolokiaUrl;
    
    public ActiveMQQueueService(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        // Standardmäßig läuft Jolokia auf Port 8161
        this.jolokiaUrl = "http://localhost:8161/api/jolokia";
    }
    
    /**
     * Konstruktor mit custom URL
     */
    public ActiveMQQueueService(RestTemplate restTemplate, ObjectMapper objectMapper, String activeMQHost, int jolokiaPort) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.jolokiaUrl = String.format("http://%s:%d/api/jolokia", activeMQHost, jolokiaPort);
    }
    
    /**
     * Fragt alle Queue-Namen von ActiveMQ über Jolokia ab (GET-Request)
     * @param brokerName Name des Brokers (standardmäßig "localhost")
     * @return Liste aller Queue-Namen
     * @throws Exception bei Verbindungs- oder Parsing-Fehlern
     */
    public List<String> getAllQueueNames(String brokerName) throws Exception {
        List<String> queueNames = new ArrayList<>();
        
        // Jolokia Search Request für alle Queue MBeans
        String mBeanPattern = String.format("org.apache.activemq:type=Broker,brokerName=%s,destinationType=Queue,destinationName=*", brokerName);
        String requestUrl = String.format("%s/search/%s", jolokiaUrl, mBeanPattern);
        
        // HTTP Headers setzen
        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");
        headers.set("Accept", "application/json");
        
        HttpEntity<String> entity = new HttpEntity<>(headers);
        
        // GET Request ausführen
        ResponseEntity<String> response = restTemplate.exchange(
            requestUrl, 
            HttpMethod.GET, 
            entity, 
            String.class
        );
        
        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            JsonNode jsonResponse = objectMapper.readTree(response.getBody());
            JsonNode valueNode = jsonResponse.get("value");
            
            if (valueNode != null && valueNode.isArray()) {
                for (JsonNode mBeanName : valueNode) {
                    String queueName = extractQueueNameFromMBean(mBeanName.asText());
                    if (queueName != null && !queueName.isEmpty()) {
                        queueNames.add(queueName);
                    }
                }
            }
        } else {
            throw new RuntimeException("Fehler beim Abrufen der Queue-Liste. HTTP Status: " + response.getStatusCode());
        }
        
        return queueNames;
    }
    
    /**
     * Alternative Methode mit POST-Request für komplexere Abfragen
     * @param brokerName Name des Brokers
     * @return Liste aller Queue-Namen
     * @throws Exception bei Verbindungs- oder Parsing-Fehlern
     */
    public List<String> getAllQueueNamesWithPost(String brokerName) throws Exception {
        List<String> queueNames = new ArrayList<>();
        
        // JSON Request Body für Jolokia
        String requestBody = String.format(
            "{\"type\":\"search\",\"mbean\":\"org.apache.activemq:type=Broker,brokerName=%s,destinationType=Queue,destinationName=*\"}",
            brokerName
        );
        
        // HTTP Headers setzen
        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");
        headers.set("Accept", "application/json");
        
        HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);
        
        // POST Request ausführen
        ResponseEntity<String> response = restTemplate.exchange(
            jolokiaUrl, 
            HttpMethod.POST, 
            entity, 
            String.class
        );
        
        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            JsonNode jsonResponse = objectMapper.readTree(response.getBody());
            JsonNode valueNode = jsonResponse.get("value");
            
            if (valueNode != null && valueNode.isArray()) {
                for (JsonNode mBeanName : valueNode) {
                    String queueName = extractQueueNameFromMBean(mBeanName.asText());
                    if (queueName != null && !queueName.isEmpty()) {
                        queueNames.add(queueName);
                    }
                }
            }
        } else {
            throw new RuntimeException("Fehler beim Abrufen der Queue-Liste. HTTP Status: " + response.getStatusCode());
        }
        
        return queueNames;
    }
    
    /**
     * Methode mit Basic Authentication
     * @param brokerName Name des Brokers
     * @param username Benutzername für ActiveMQ
     * @param password Passwort für ActiveMQ
     * @return Liste aller Queue-Namen
     * @throws Exception bei Verbindungs- oder Parsing-Fehlern
     */
    public List<String> getAllQueueNamesWithAuth(String brokerName, String username, String password) throws Exception {
        List<String> queueNames = new ArrayList<>();
        
        String requestBody = String.format(
            "{\"type\":\"search\",\"mbean\":\"org.apache.activemq:type=Broker,brokerName=%s,destinationType=Queue,destinationName=*\"}",
            brokerName
        );
        
        // HTTP Headers mit Basic Auth
        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");
        headers.set("Accept", "application/json");
        headers.setBasicAuth(username, password);
        
        HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);
        
        ResponseEntity<String> response = restTemplate.exchange(
            jolokiaUrl, 
            HttpMethod.POST, 
            entity, 
            String.class
        );
        
        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            JsonNode jsonResponse = objectMapper.readTree(response.getBody());
            JsonNode valueNode = jsonResponse.get("value");
            
            if (valueNode != null && valueNode.isArray()) {
                for (JsonNode mBeanName : valueNode) {
                    String queueName = extractQueueNameFromMBean(mBeanName.asText());
                    if (queueName != null && !queueName.isEmpty()) {
                        queueNames.add(queueName);
                    }
                }
            }
        } else {
            throw new RuntimeException("Fehler beim Abrufen der Queue-Liste. HTTP Status: " + response.getStatusCode());
        }
        
        return queueNames;
    }
    
    /**
     * Extrahiert den Queue-Namen aus dem MBean-Namen
     * @param mBeanName vollständiger MBean-Name
     * @return Queue-Name oder null falls nicht gefunden
     */
    private String extractQueueNameFromMBean(String mBeanName) {
        Pattern pattern = Pattern.compile("destinationName=([^,]+)");
        Matcher matcher = pattern.matcher(mBeanName);
        
        if (matcher.find()) {
            return matcher.group(1);
        }
        
        return null;
    }
    
    /**
     * Gibt detaillierte Queue-Informationen zurück
     * @param brokerName Name des Brokers
     * @return Liste mit Queue-Namen und zusätzlichen Informationen
     * @throws Exception bei Fehlern
     */
    public List<QueueInfo> getDetailedQueueInfo(String brokerName) throws Exception {
        List<QueueInfo> queueInfos = new ArrayList<>();
        List<String> queueNames = getAllQueueNames(brokerName);
        
        for (String queueName : queueNames) {
            // Detaillierte Informationen für jede Queue abrufen
            String mBean = String.format("org.apache.activemq:type=Broker,brokerName=%s,destinationType=Queue,destinationName=%s", 
                                       brokerName, queueName);
            String requestBody = String.format(
                "{\"type\":\"read\",\"mbean\":\"org.apache.activemq:type=Broker,brokerName=%s,destinationType=Queue,destinationName=%s\"}",
                brokerName, queueName
            );
            
            HttpHeaders headers = new HttpHeaders();
            headers.set("Content-Type", "application/json");
            
            HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);
            
            try {
                ResponseEntity<String> response = restTemplate.exchange(
                    jolokiaUrl, 
                    HttpMethod.POST, 
                    entity, 
                    String.class
                );
                
                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    JsonNode jsonResponse = objectMapper.readTree(response.getBody());
                    JsonNode valueNode = jsonResponse.get("value");
                    
                    if (valueNode != null) {
                        QueueInfo info = new QueueInfo();
                        info.setName(queueName);
                        info.setQueueSize(valueNode.path("QueueSize").asLong(0));
                        info.setConsumerCount(valueNode.path("ConsumerCount").asLong(0));
                        info.setEnqueueCount(valueNode.path("EnqueueCount").asLong(0));
                        info.setDequeueCount(valueNode.path("DequeueCount").asLong(0));
                        queueInfos.add(info);
                    }
                }
            } catch (Exception e) {
                // Bei Fehlern für einzelne Queue, trotzdem fortfahren
                System.err.println("Fehler beim Abrufen der Details für Queue " + queueName + ": " + e.getMessage());
            }
        }
        
        return queueInfos;
    }
    
    // Hilfsklasse für detaillierte Queue-Informationen
    public static class QueueInfo {
        private String name;
        private long queueSize;
        private long consumerCount;
        private long
