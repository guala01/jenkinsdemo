pipeline {
    agent any

    environment {
        SONARQUBE_SERVER = 'SonarQube' // Update with your SonarQube server configuration name in Jenkins
        SONAR_HOST_URL = 'http://localhost:9000' // Adjust if needed
        SONARQUBE_LOGIN = 'admin' // Replace with your SonarQube username
        SONARQUBE_PASSWORD = 'Freddie96!@!' // Replace with your SonarQube password
        PROJECT_KEY = 'testproj' // Replace with your SonarQube project key
    }

    stages {
        stage('Git Checkout') {
            steps {
                git branch: 'main',
                    url: 'https://github.com/guala01/jenkinsdemo.git'
            }
        }

        stage('Build Gradle Project') {
            steps {
                // Use the Gradle Wrapper to build the project
                bat 'gradlew.bat test'
                bat 'gradlew.bat clean build --quiet'
            }
        }

        stage('Initial SonarQube Analysis') {
            steps {
                withSonarQubeEnv("${SONARQUBE_SERVER}") {
                    withCredentials([string(credentialsId: 'sonarqube', variable: 'SONAR_TOKEN')]) {
                        // Run initial SonarQube analysis
                        bat """
                            gradlew.bat clean build jacocoTestReport sonar -Dsonar.login=%SONAR_TOKEN% -Dsonar.projectKey=${PROJECT_KEY} -Dsonar.projectName=${PROJECT_KEY}
                        """
                    }
                }
            }
        }
        stage('Fetch SonarQube Issues, Generate Tests, and Validate Coverage') {
            steps {
                script {
                    // Encode credentials for Basic Auth
                    def auth = "${SONARQUBE_LOGIN}:${SONARQUBE_PASSWORD}".bytes.encodeBase64().toString()

                    // Fetch SonarQube coverage data
                    bat """
                        curl -s -u ${SONARQUBE_LOGIN}:${SONARQUBE_PASSWORD} ^
                        "%SONAR_HOST_URL%/api/measures/component_tree?component=%PROJECT_KEY%&metricKeys=coverage&qualifiers=FIL" ^
                        -o sonar_issues.json
                    """

                    // Read the JSON file
                    def sonarIssues = readJSON file: 'sonar_issues.json'

                    // Iterate over each component (file)
                    sonarIssues.components.each { component ->
                        def filePath = component.path.replace('/', '\\') // Adjust path for Windows
                        def language = component.language

                        // Skip if no coverage data
                        if (component.measures.size() == 0) {
                            return
                        }

                        def coverage = Float.parseFloat(component.measures[0].value)
                        echo "File Path: ${filePath}"
                        echo "Coverage: ${coverage}"

                        // Skip if coverage is already acceptable (e.g., > 80%)
                        if (coverage > 80) {
                            return
                        }

                        // Read the file content
                        def fileContent = readFile(file: filePath)

                        // Prepare the prompt for the LLM API
                        def prompt = """
[INST]Ti fornirò il percorso di un file Java (versione 1.8) e il suo contenuto. La tua missione è di creare uno unit test utilizzando JUnit 4.11. Utilizza tutte le best pracitices e gli ultimi coding standards. È cruciale che il file di test sia compilabile e che tu includa tutti gli import necessari, **incluso un import esplicito del file Java target** specificato da ${filePath}.

Quando crei il file di test:
- Assicurati di importare esplicitamente la classe Java target usando il percorso fornito (${filePath}). Questo è essenziale per garantire che il file di test possa accedere alla classe che deve testare.
- Fornisci il percorso del file di test generato e includi il contenuto del file di test, seguendo le convenzioni di denominazione standard per i test (ad esempio, se il file originale è `MyClass.java`, il file di test dovrebbe essere `MyClassTest.java`).
- Copri tutti i metodi pubblici della classe target con almeno un caso di test ciascuno.

Path file originale: .\\${filePath}

Contenuto del file originale:
${fileContent}

Fornisci il file di test nel seguente formato:

TEST_PATH: [Inserisci qui il percorso del file di test]

CONTENT_FILE:
${language}
[Inserisci qui il contenuto del file di test, assicurandoti di includere l'import del file Java target come indicato sopra]

[/INST]
""".trim()

                        // Prepare the request body for the LLM API
                        def requestBody = [
                            model: "qwen2.5-coder-14b-instruct",
                            messages: [
                                [role: "user", content: prompt]
                            ],
                            temperature: 0.2,
                            max_tokens: 1000,
                            stream: false
                        ]
                        // Write request body to JSON file
                        def inputFileName = "input-${filePath.replace('\\', '_')}.json"
                        writeJSON file: inputFileName, json: requestBody

                        // Call the LLM API
                        bat """
                            curl --fail -s -X POST "http://127.0.0.1:1234/v1/chat/completions" ^
                                -H "Content-Type: application/json" ^
                                -d @${inputFileName} ^
                                -o response-${filePath.replace('\\', '_')}.json
                        """

                        // Read and process the response
                        def responseFile = "response-${filePath.replace('\\', '_')}.json"
                        def response = readJSON file: responseFile
                        def generatedText = response.choices[0].message.content

                        // Parse generatedText to extract test file path and content
                        if (!generatedText.contains('TEST_PATH:') || !generatedText.contains('CONTENT_FILE:')) {
                            echo "Invalid response from LLM API"
                            return
                        }

                        def testFilePath = generatedText.split('TEST_PATH:')[1].split('CONTENT_FILE')[0].trim()
                        def testCode = ''

                        if (generatedText.contains('```' + language)) {
                            testCode = generatedText.split('```' + language)[1].split('```')[0].trim()
                        } else {
                            echo "Language code block not found in response"
                            return
                        }

                        // Write the generated test code to file
                        writeFile file: testFilePath, text: testCode

                        // Rebuild the project and run tests
                        def buildStatus = bat returnStatus: true, script: 'gradlew.bat clean build'

                        if (buildStatus != 0) {
                            echo "Build failed after adding generated test. Discarding changes."
                            bat "del ${testFilePath}"
                            return
                        }

                        // Perform a new SonarQube scan
                        withSonarQubeEnv("${SONARQUBE_SERVER}") {
                            withCredentials([string(credentialsId: 'sonarqube', variable: 'SONAR_TOKEN')]) {
                                bat """
                                    gradlew.bat sonar -Dsonar.login=%SONAR_TOKEN% -Dsonar.projectKey=${PROJECT_KEY} -Dsonar.projectName=${PROJECT_KEY}
                                """
                            }
                        }

                        // Fetch new coverage data
                        bat """
                            curl -s -u ${SONARQUBE_LOGIN}:${SONARQUBE_PASSWORD} ^
                            "%SONAR_HOST_URL%/api/measures/component_tree?component=%PROJECT_KEY%&metricKeys=coverage&qualifiers=FIL" ^
                            -o sonar_new_coverage.json
                        """

                        def sonarNewCoverage = readJSON file: 'sonar_new_coverage.json'

                        // Compare coverage
                        def newCoverage = coverage
                        sonarNewCoverage.components.each { newComponent ->
                            if (newComponent.path == component.path) {
                                newCoverage = Float.parseFloat(newComponent.measures[0].value)
                            }
                        }

                        if (newCoverage > coverage) {
                            echo "Coverage improved for ${component.path} from ${coverage}% to ${newCoverage}%"
                            // Optionally, push changes or notify team
                        } else {
                            echo "No coverage improvement for ${component.path}. Discarding generated test."
                            bat "del ${testFilePath}"
                        }
                    }
                }
            }
        }
    }
}
