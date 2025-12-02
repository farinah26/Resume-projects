AI Admissions FAQ Chatbot

End-to-end AI chatbot using AWS Lambda (Java), DynamoDB, Amazon Bedrock, API Gateway, and an S3-hosted frontend

Overview:

This project is a fully serverless AI-based FAQ assistant designed to answer graduate admissions questions. The backend is implemented with a Java Lambda function that reads FAQ data stored in DynamoDB and calls an Amazon Bedrock Claude model to generate natural language responses. The frontend is a simple web chat interface hosted on S3 that communicates with the backend through an API Gateway HTTP endpoint.

The goal of this project is to demonstrate practical experience in cloud architecture, backend engineering, and generative AI integration, as well as full stack deployment on AWS.

Architecture:
Web Browser (S3 Static Frontend)
        |
        | POST request to /chat
        v
API Gateway (HTTP API) -----> AWS Lambda (Java)
                                   |
                                   | scan()
                                   v
                            DynamoDB (FAQTable)
                                   |
                                   | invokeModel()
                                   v
                            Amazon Bedrock (Claude)

Technologies Used:

AWS Lambda (Java 17)

Amazon Bedrock (Claude Sonnet family model)

Amazon DynamoDB

Amazon API Gateway (HTTP API)

Amazon S3 (static frontend hosting)

IAM, CloudWatch

Maven

HTML, CSS, JavaScript


Folder Structure:
ai-admissions-faq-chatbot/
│
├── backend/
│   ├── pom.xml
│   └── src/
│
├── frontend/
│   ├── index.html
│   ├── styles.css
│   └── script.js
│
├── .gitignore
└── README.md

Frontend API Call:

    Located in frontend/script.js:

    const API_URL = "https://<your-api-id>.execute-api.<region>.amazonaws.com/chat";

Example Frontend Usage:

    A user enters a question such as:

    What are the admission requirements?


The UI sends a POST request to the API Gateway endpoint. The backend loads relevant FAQ entries, builds a prompt, calls Bedrock, and returns a natural language answer.

Example Lambda Response:
{
  "answer": "Graduate applicants typically need to submit transcripts, a statement of purpose, recommendation letters, and any required test scores such as the GRE or English proficiency exams. Requirements may vary by program."
}


Deployment Summary:

1.Build backend using Maven

2.Upload JAR to AWS Lambda and configure environment variables

3.Create API Gateway route POST /chat and integrate with Lambda

4.Enable CORS for browser access

5.Upload frontend to S3 and enable static hosting

6.Insert the API URL into script.js


Future Enhancements:

Add vector search or embeddings for better question matching

Improve chat UI and interaction features

Add user analytics and logging

Add optional authentication