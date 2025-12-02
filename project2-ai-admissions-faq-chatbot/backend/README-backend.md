AI Admissions FAQ Chatbot Backend

AWS Lambda (Java) + DynamoDB + Amazon Bedrock + API Gateway

Overview:

This backend provides the core logic for an AI-powered admissions FAQ assistant. It is implemented in Java 17 as an AWS Lambda function. The Lambda function retrieves FAQ entries from DynamoDB, builds a context-aware prompt, and invokes an Amazon Bedrock Claude model to generate clear, natural-language answers for users. The response is returned as a JSON structure that is consumed by the frontend via API Gateway.

Features:

-Serverless Java backend using AWS Lambda

-Real-time GenAI response generation through Amazon Bedrock

-DynamoDB integration for structured FAQ storage

-Clean JSON request and response model

-Easily deployed and scalable

Project Structure:
backend/
├── pom.xml
└── src/
    └── main/
        └── java/
            └── com/
                └── farina/
                    └── chatbot/
                        ├── ChatbotHandler.java
                        ├── ChatRequest.java
                        └── ChatResponse.java


Request / Response Model:

Request format:
{
  "question": "What are the admission requirements?"
}


Response format:
{
  "answer": "To apply for graduate programs..."
}

Environment Variables Required:
Key:	                Value:
FAQ_TABLE	            FAQTable
BEDROCK_MODEL_ID	    The model ID used in Bedrock (example: anthropic claude-3-sonnet-20240229-v1:0)


Lambda Handler Configuration:
    com.farina.chatbot.ChatbotHandler::handleRequest

Build and Package:
    Run Maven from the backend directory:
    mvn clean package


The built artifact will appear in:

    target/faq-chatbot-lambda.jar

->Upload this JAR to the AWS Lambda console.


Deployment Steps (Back End):

1.Create a DynamoDB table named FAQTable

2.Deploy Lambda and upload the packaged JAR

3.Set required environment variables

4.Create an API Gateway HTTP API route POST /chat integrated with this Lambda

5.Enable CORS in API Gateway for POST requests