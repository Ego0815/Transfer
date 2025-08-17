import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

class SCMManagerClient {
    private String baseUrl
    private String apiToken
    private JsonSlurper jsonSlurper

    SCMManagerClient(String baseUrl, String apiToken) {
        this.baseUrl = baseUrl.endsWith('/') ? baseUrl[0..-2] : baseUrl
        this.apiToken = apiToken
        this.jsonSlurper = new JsonSlurper()
    }

    /**
     * Führt HTTP Request aus
     */
    private Map executeRequest(String endpoint, String method = 'GET', Map requestBody = null, String contentType = 'application/json') {
        try {
            URL url = new URL("${baseUrl}${endpoint}")
            HttpURLConnection connection = (HttpURLConnection) url.openConnection()
            
            // Headers setzen
            connection.setRequestMethod(method)
            connection.setRequestProperty('Authorization', "Bearer ${apiToken}")
            connection.setRequestProperty('Content-Type', contentType)
            connection.setRequestProperty('Accept', 'application/json')
            
            // Request Body für POST/PUT
            if (requestBody && (method == 'POST' || method == 'PUT')) {
                connection.setDoOutput(true)
                String jsonString = new JsonBuilder(requestBody).toString()
                
                connection.outputStream.withWriter('UTF-8') { writer ->
                    writer.write(jsonString)
                }
            }
            
            // Response verarbeiten
            int responseCode = connection.responseCode
            String responseText = ''
            
            if (responseCode < 400) {
                responseText = connection.inputStream.text
            } else {
                responseText = connection.errorStream?.text ?: "HTTP ${responseCode}"
            }
            
            return [
                statusCode: responseCode,
                body: responseText,
                success: responseCode < 400
            ]
            
        } catch (Exception e) {
            return [
                statusCode: 0,
                body: "Connection error: ${e.message}",
                success: false,
                error: e.message
            ]
        }
    }

    /**
     * Testet die Authentifizierung
     */
    boolean testAuthentication() {
        println "🔐 Teste API Token Authentifizierung..."
        
        Map response = executeRequest('/v2/me')
        
        if (response.success) {
            def user = jsonSlurper.parseText(response.body)
            println "✅ Authentifizierung erfolgreich als: ${user.displayName}"
            println "   Email: ${user.mail}"
            return true
        } else {
            println "❌ Authentifizierung fehlgeschlagen. Status: ${response.statusCode}"
            println "   Response: ${response.body}"
            return false
        }
    }

    /**
     * Erstellt einen Pull Request
     */
    def createPullRequest(String namespace, String repository, Map pullRequestData) {
        println "🚀 Erstelle Pull Request für ${namespace}/${repository}..."
        
        // Validierung der Pflichtfelder
        if (!pullRequestData.source) {
            throw new IllegalArgumentException("❌ Pflichtfeld 'source' fehlt")
        }
        if (!pullRequestData.target) {
            throw new IllegalArgumentException("❌ Pflichtfeld 'target' fehlt")
        }
        if (!pullRequestData.title) {
            throw new IllegalArgumentException("❌ Pflichtfeld 'title' fehlt")
        }
        
        String endpoint = "/v2/pull-requests/${namespace}/${repository}"
        String contentType = "application/vnd.scmm-pullRequest+json;v=2"
        
        Map response = executeRequest(endpoint, 'POST', pullRequestData, contentType)
        
        if (response.success) {
            def pr = jsonSlurper.parseText(response.body)
            println "✅ Pull Request erfolgreich erstellt!"
            println "   ID: ${pr.id}"
            println "   Title: ${pr.title}"
            println "   Source: ${pr.source} → Target: ${pr.target}"
            println "   URL: ${baseUrl}/repo/${namespace}/${repository}/pull-request/${pr.id}"
            return pr
        } else {
            println "❌ Pull Request konnte nicht erstellt werden. Status: ${response.statusCode}"
            println "   Response: ${response.body}"
            return null
        }
    }

    /**
     * Listet Branches eines Repositories auf
     */
    def listBranches(String namespace, String repository) {
        println "📂 Lade Branches für ${namespace}/${repository}..."
        
        String endpoint = "/v2/repositories/${namespace}/${repository}/branches"
        Map response = executeRequest(endpoint)
        
        if (response.success) {
            def data = jsonSlurper.parseText(response.body)
            def branches = data._embedded?.branches ?: []
            
            println "   Verfügbare Branches:"
            branches.each { branch ->
                println "   - ${branch.name}"
            }
            return branches
        } else {
            println "❌ Branches konnten nicht geladen werden: ${response.body}"
            return []
        }
    }

    /**
     * Prüft ob Repository existiert
     */
    boolean repositoryExists(String namespace, String repository) {
        String endpoint = "/v2/repositories/${namespace}/${repository}"
        Map response = executeRequest(endpoint)
        return response.success
    }
}

// ===========================================
// JENKINS PIPELINE FUNKTIONEN
// ===========================================

/**
 * Hauptfunktion für Jenkins Pipeline
 */
def createPullRequestInJenkins(Map config) {
    // Validierung der Parameter
    if (!config.scmUrl || !config.apiToken || !config.namespace || !config.repository) {
        error "❌ Fehlende Konfiguration. Benötigt: scmUrl, apiToken, namespace, repository"
    }
    
    println "🔧 SCM Manager Pull Request Creator (Jenkins Pipeline)"
    println "=" * 60
    
    // Client erstellen
    SCMManagerClient client = new SCMManagerClient(config.scmUrl, config.apiToken)
    
    try {
        // 1. Authentifizierung testen
        if (!client.testAuthentication()) {
            error "❌ API Token Authentifizierung fehlgeschlagen"
        }
        
        // 2. Repository prüfen
        if (!client.repositoryExists(config.namespace, config.repository)) {
            error "❌ Repository ${config.namespace}/${config.repository} nicht gefunden"
        }
        
        // 3. Branches anzeigen (optional)
        if (config.showBranches) {
            client.listBranches(config.namespace, config.repository)
        }
        
        // 4. Pull Request Daten vorbereiten (SCM-Manager v2 Schema)
        Map pullRequestData = [:]
        
        // PFLICHTFELDER
        pullRequestData.source = config.sourceBranch
        pullRequestData.target = config.targetBranch ?: 'main'  
        pullRequestData.title = config.title ?: "Automated Pull Request from Jenkins"
        
        // OPTIONALE FELDER
        if (config.description) {
            pullRequestData.description = config.description
        } else {
            pullRequestData.description = "This PR was created automatically by Jenkins Pipeline.\n\nBuild: ${env.BUILD_NUMBER ?: 'unknown'}\nJob: ${env.JOB_NAME ?: 'unknown'}"
        }
        
        // Validierung der Pflichtfelder
        if (!pullRequestData.source) {
            error "❌ Source Branch ist erforderlich (sourceBranch)"
        }
        if (!pullRequestData.target) {
            error "❌ Target Branch ist erforderlich (targetBranch)"  
        }
        if (!pullRequestData.title) {
            error "❌ Pull Request Title ist erforderlich (title)"
        }
        
        // Optional: Status setzen (DRAFT, OPEN)
        if (config.status) {
            pullRequestData.status = config.status
        }
        
        // Optional: Reviewer hinzufügen
        if (config.reviewers) {
            pullRequestData.reviewer = config.reviewers.collect { reviewerId ->
                [id: reviewerId, approved: false]
            }
        }
        
        // Optional: Labels hinzufügen  
        if (config.labels) {
            pullRequestData.labels = config.labels
        }
        
        // Optional: Source Branch nach Merge löschen
        if (config.shouldDeleteSourceBranch != null) {
            pullRequestData.shouldDeleteSourceBranch = config.shouldDeleteSourceBranch
        }
        
        // 5. Pull Request erstellen
        def result = client.createPullRequest(config.namespace, config.repository, pullRequestData)
        
        if (result) {
            println "\n🎉 Pull Request erfolgreich erstellt!"
            
            // Jenkins Build Properties setzen
            if (binding.hasVariable('currentBuild')) {
                currentBuild.description = "PR #${result.id}: ${result.title}"
                
                // Custom Properties für weitere Pipeline Steps
                env.SCM_PR_ID = result.id
                env.SCM_PR_URL = "${config.scmUrl}/repo/${config.namespace}/${config.repository}/pull-request/${result.id}"
            }
            
            return result
        } else {
            error "❌ Pull Request konnte nicht erstellt werden"
        }
        
    } catch (Exception e) {
        error "❌ Fehler beim Erstellen des Pull Requests: ${e.message}"
    }
}

// ===========================================
// JENKINS PIPELINE USAGE BEISPIELE
// ===========================================

/*
// Beispiel 1: Einfache Verwendung in Jenkins Pipeline
node {
    stage('Create Pull Request') {
        // SCM Manager Konfiguration
        def config = [
            scmUrl: 'https://scm.your-company.com',
            apiToken: credentials('scm-api-token'),  // Jenkins Credential ID
            namespace: 'myteam',
            repository: 'myproject',
            sourceBranch: env.BRANCH_NAME,           // Aktueller Branch
            targetBranch: 'develop',
            title: "Feature: ${env.BUILD_NUMBER} - Auto PR",
            description: """
                Automatischer Pull Request erstellt von Jenkins.
                
                **Build Details:**
                - Job: ${env.JOB_NAME}
                - Build: ${env.BUILD_NUMBER}
                - Branch: ${env.BRANCH_NAME}
                - Commit: ${env.GIT_COMMIT?.take(8)}
                
                **Änderungen:**
                - Feature implementiert
                - Tests hinzugefügt
                """.stripIndent(),
            status: 'OPEN',                          // DRAFT oder OPEN
            reviewers: ['john.doe', 'jane.smith'],   // Optional: Reviewer IDs
            labels: ['jenkins', 'automated'],        // Optional: Labels
            shouldDeleteSourceBranch: true,          // Optional: Branch nach Merge löschen
            showBranches: false
        ]
        
        def pullRequest = createPullRequestInJenkins(config)
        
        echo "Pull Request erstellt: ${env.SCM_PR_URL}"
    }
}

// Beispiel 2: Declarative Pipeline
pipeline {
    agent any
    
    environment {
        SCM_API_TOKEN = credentials('scm-api-token')
        SCM_URL = 'https://scm.your-company.com'
    }
    
    stages {
        stage('Build') {
            steps {
                echo 'Building application...'
                // Dein Build Code hier
            }
        }
        
        stage('Create PR') {
            when {
                not { branch 'main' }
                not { branch 'develop' }
            }
            steps {
                script {
                    def config = [
                        scmUrl: env.SCM_URL,
                        apiToken: env.SCM_API_TOKEN,
                        namespace: 'myteam',
                        repository: 'myproject',
                        sourceBranch: env.BRANCH_NAME,
                        targetBranch: 'develop',
                        title: "Auto PR: ${env.BRANCH_NAME}",
                        description: "Automated PR from Jenkins build #${env.BUILD_NUMBER}"
                    ]
                    
                    createPullRequestInJenkins(config)
                }
            }
        }
    }
    
    post {
        success {
            echo "Pipeline erfolgreich! PR URL: ${env.SCM_PR_URL}"
        }
    }
}

// Beispiel 3: Mit Git Integration
stage('Auto PR nach erfolgreichem Build') {
    when {
        allOf {
            not { branch 'main' }
            expression { currentBuild.result == null || currentBuild.result == 'SUCCESS' }
        }
    }
    steps {
        script {
            // Git Informationen sammeln
            def gitCommit = sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
            def gitMessage = sh(returnStdout: true, script: 'git log -1 --pretty=%B').trim()
            def gitAuthor = sh(returnStdout: true, script: 'git log -1 --pretty=%an').trim()
            
            def config = [
                scmUrl: 'https://scm.your-company.com',
                apiToken: credentials('scm-api-token'),
                namespace: 'myteam',
                repository: 'myproject',
                sourceBranch: env.BRANCH_NAME,
                targetBranch: 'develop',
                title: "PR: ${env.BRANCH_NAME} - ${gitMessage.split('\n')[0]}",
                description: """
                    **Git Details:**
                    - Commit: `${gitCommit.take(8)}`
                    - Author: ${gitAuthor}
                    - Message: ${gitMessage}
                    
                    **Jenkins Build:**
                    - Job: ${env.JOB_NAME}
                    - Build: #${env.BUILD_NUMBER}
                    - Started by: ${env.BUILD_USER ?: 'System'}
                    """.stripIndent()
            ]
            
            createPullRequestInJenkins(config)
        }
    }
}
*/

// ===========================================
// STANDALONE EXECUTION (für Tests)
// ===========================================
if (this.args) {
    println "🧪 Standalone Test Mode"
    
    // Test Konfiguration
    def testConfig = [
        scmUrl: 'https://your-scm-manager.example.com',
        apiToken: 'your-api-token-here',
        namespace: 'myteam',
        repository: 'myproject',
        sourceBranch: 'feature/test-branch',
        targetBranch: 'develop',
        title: 'Test PR from Groovy Script',
        description: 'This is a test pull request created by the Groovy script.',
        showBranches: true
    ]
    
    createPullRequestInJenkins(testConfig)
}
