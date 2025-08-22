import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class ActiveMQQueueManager {
    
    private final String jolokiaUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    
    public ActiveMQQueueManager(String activeMQHost, int jolokiaPort) {
        this.jolokiaUrl = String.format("http://%s:%d/api/jolokia", activeMQHost, jolokiaPort);
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
    }
    
    /**
     * Fragt alle Queue-Namen von ActiveMQ über Jolokia ab
     * @param brokerName Name des Brokers (standardmäßig "localhost")
     * @return Liste aller Queue-Namen
     * @throws IOException bei Verbindungs- oder Parsing-Fehlern
     */
    public List<String> getAllQueueNames(String brokerName) throws IOException, InterruptedException {
        List<String> queueNames = new ArrayList<>();
        
        // Jolokia Request für alle Queue MBeans
        String mBeanPattern = String.format("org.apache.activemq:type=Broker,brokerName=%s,destinationType=Queue,destinationName=*", brokerName);
        String requestUrl = String.format("%s/search/%s", jolokiaUrl, mBeanPattern);
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(requestUrl))
                .GET()
                .header("Content-Type", "application/json")
                .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() == 200) {
            JsonNode jsonResponse = objectMapper.readTree(response.body());
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
            throw new IOException("Fehler beim Abrufen der Queue-Liste. HTTP Status: " + response.statusCode());
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
     * Alternative Methode mit POST-Request für komplexere Abfragen
     * @param brokerName Name des Brokers
     * @return Liste aller Queue-Namen
     * @throws IOException bei Verbindungs- oder Parsing-Fehlern
     */
    public List<String> getAllQueueNamesWithPost(String brokerName) throws IOException, InterruptedException {
        List<String> queueNames = new ArrayList<>();
        
        // JSON Request Body für Jolokia
        String requestBody = String.format(
            "{\"type\":\"search\",\"mbean\":\"org.apache.activemq:type=Broker,brokerName=%s,destinationType=Queue,destinationName=*\"}",
            brokerName
        );
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(jolokiaUrl))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .header("Content-Type", "application/json")
                .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() == 200) {
            JsonNode jsonResponse = objectMapper.readTree(response.body());
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
            throw new IOException("Fehler beim Abrufen der Queue-Liste. HTTP Status: " + response.statusCode());
        }
        
        return queueNames;
    }
    
    /**
     * Hauptmethode zum Testen
     */
    public static void main(String[] args) {
        try {
            // ActiveMQ läuft standardmäßig auf Port 8161 für Jolokia
            ActiveMQQueueManager manager = new ActiveMQQueueManager("localhost", 8161);
            
            // Queue-Namen abrufen (Broker-Name ist standardmäßig "localhost")
            List<String> queueNames = manager.getAllQueueNames("localhost");
            
            System.out.println("Gefundene Queues:");
            for (String queueName : queueNames) {
                System.out.println("- " + queueName);
            }
            
        } catch (Exception e) {
            System.err.println("Fehler beim Abrufen der Queue-Liste: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
