pipeline {
    agent any

    environment {
        SONARQUBE_SERVER = 'SonarQube'
        SONAR_HOST_URL = 'http://localhost:9000'
        SONARQUBE_LOGIN = 'admin'
        SONARQUBE_PASSWORD = 'Freddie96!@!'
        PROJECT_KEY = 'testproj'
    }

    stages {
        stage('Git Checkout') {
            steps {
                // Clean workspace
                bat 'if exist * del /q *'
                git branch: 'main',
                    url: 'https://github.com/guala01/jenkinsdemo.git'
            }
        }

        stage('Fetch and Fix SonarQube Issues') {
            steps {
                script {
                    // Fetch SonarQube issues
                    withCredentials([string(credentialsId: 'sonarqube', variable: 'SONAR_TOKEN')]) {
                        bat """
                            curl -s -u %SONARQUBE_LOGIN%:%SONARQUBE_PASSWORD% ^
                            "%SONAR_HOST_URL%/api/issues/search?componentKeys=%PROJECT_KEY%&s=FILE_LINE&statuses=OPEN,CONFIRMED&ps=100" ^
                            -o sonar_issues.json
                        """
                    }

                    def sonarIssues = readJSON file: 'sonar_issues.json'
                    
                    sonarIssues.issues.each { issue ->
                        def issueKey = issue.key
                        def filePath = issue.component.split(':')[1]
                        
                        // Get rule details
                        def ruleKey = URLEncoder.encode(issue.rule, "UTF-8").replace("%3A", ":")
                        echo "Processing rule: ${ruleKey}"
                        withCredentials([string(credentialsId: 'sonarqube', variable: 'SONAR_TOKEN')]) {
                            bat """
                                curl -s -u %SONARQUBE_LOGIN%:%SONARQUBE_PASSWORD% ^
                                "%SONAR_HOST_URL%/api/rules/show?key=${ruleKey}" ^
                                -o rule_details.json
                            """
                        }

                        def ruleDetails = readJSON file: 'rule_details.json'
                        def ruleName = ruleDetails.rule.name
                        def ruleDesc = ruleDetails.rule.mdDesc.replaceAll(/<[^>]+>/, '').trim()

                        // Generate fix using your existing LLM API call structure
                        def fixedCode = generateFix(filePath, ruleName, ruleDesc)
                        
                        if (fixedCode) {
                            // Apply fix
                            writeFile file: filePath, text: fixedCode
                            
                            // Validate fix with build and SonarQube analysis
                            def buildSuccess = bat(returnStatus: true, script: 'gradlew.bat clean build')
                            
                            if (buildSuccess == 0) {
                                // Run SonarQube analysis
                                withSonarQubeEnv("${SONARQUBE_SERVER}") {
                                    withCredentials([string(credentialsId: 'sonarqube', variable: 'SONAR_TOKEN')]) {
                                        bat """
                                            gradlew.bat sonar -Dsonar.login=%SONAR_TOKEN% -Dsonar.projectKey=${PROJECT_KEY}
                                        """
                                    }
                                }
                                
                                // Verify if issue is fixed
                                sleep(time: 10, unit: 'SECONDS') // Wait for SonarQube analysis
                                def issueFixed = verifySonarIssueFixed(issueKey)
                                
                                if (issueFixed) {
                                    echo "Successfully fixed issue: ${issueKey}"
                                    // Here you could add code to commit and push changes
                                } else {
                                    echo "Fix verification failed for issue: ${issueKey}"
                                    // Revert changes if needed
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

def generateFix(filePath, ruleName, ruleDesc) {
    def fileContent = readFile(filePath)
    def prompt = """
[INST] Fix the following Java code (version 1.8) to respect the rule:
Rule violated: ${ruleName}
Rule description: ${ruleDesc}

Original code:
${fileContent}

Provide only the fixed code without explanations.
[/INST]
"""
    
    def requestBody = [
        model: "qwen2.5-coder-14b-instruct",
        messages: [[role: "user", content: prompt]],
        temperature: 0.2,
        max_tokens: 1000
    ]
    
    writeJSON file: "fix_request.json", json: requestBody
    
    bat """
        curl -s -X POST "http://127.0.0.1:1234/v1/chat/completions" ^
        -H "Content-Type: application/json" ^
        -d @fix_request.json ^
        -o fix_response.json
    """
    
    def response = readJSON file: 'fix_response.json'
    def generatedCode = response.choices[0].message.content
    
    // Clean up the response to remove markdown
    generatedCode = generatedCode.replaceAll('```java\n', '')
                                .replaceAll('```', '')
                                .trim()
    
    return generatedCode
}

def verifySonarIssueFixed(issueKey) {
    bat """
        curl -s -u %SONARQUBE_LOGIN%:%SONARQUBE_PASSWORD% ^
        "%SONAR_HOST_URL%/api/issues/search?issues=${issueKey}" ^
        -o issue_status.json
    """
    
    def issueStatus = readJSON file: 'issue_status.json'
    return issueStatus.issues.size() == 0 || issueStatus.issues[0].status == 'RESOLVED'
}
