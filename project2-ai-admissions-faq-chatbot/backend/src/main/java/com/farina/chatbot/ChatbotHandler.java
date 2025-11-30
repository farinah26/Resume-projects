package com.farina.chatbot;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;
import software.amazon.awssdk.core.SdkBytes;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.List;
import java.util.Map;

public class ChatbotHandler implements RequestHandler<ChatRequest, ChatResponse> {

    private final DynamoDbClient dynamo;
    private final BedrockRuntimeClient bedrock;
    private final String faqTableName;
    private final String modelId;

    public ChatbotHandler() {
        this.dynamo = DynamoDbClient.builder().build();
        this.bedrock = BedrockRuntimeClient.builder().build();
        this.faqTableName = System.getenv("FAQ_TABLE");  // FAQTable
        this.modelId = System.getenv("BEDROCK_MODEL_ID"); // we'll set this env var
    }

    @Override
    public ChatResponse handleRequest(ChatRequest input, Context context) {
        String userQuestion = (input != null) ? input.getQuestion() : null;

        String faqText = loadFaqsAsText();

        String prompt = buildPrompt(userQuestion, faqText);

        String modelAnswer = callBedrockModel(prompt);

        ChatResponse response = new ChatResponse();
        response.setAnswer(modelAnswer);
        return response;
    }


    private String loadFaqsAsText() {
        ScanRequest request = ScanRequest.builder()
                .tableName(faqTableName)
                .limit(10)
                .build();

        ScanResponse result = dynamo.scan(request);

        StringBuilder sb = new StringBuilder();
        List<Map<String, AttributeValue>> items = result.items();

        int index = 1;
        for (Map<String, AttributeValue> item : items) {
            String q = item.containsKey("question") ? item.get("question").s() : "(no question)";
            String a = item.containsKey("answer") ? item.get("answer").s() : "(no answer)";

            sb.append(index++)
              .append(". Q: ").append(q).append("\n")
              .append("   A: ").append(a).append("\n\n");
        }

        if (sb.length() == 0) {
            sb.append("No FAQs found in table ").append(faqTableName);
        }

        return sb.toString();
    }
        private String buildPrompt(String userQuestion, String faqText) {
        if (userQuestion == null || userQuestion.trim().isEmpty()) {
            userQuestion = "Show a helpful overview of the FAQs for graduate admissions.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("You are a friendly and professional university graduate admissions assistant. ")
          .append("Use ONLY the following FAQs as your source of truth. ")
          .append("If the answer is not clearly in the FAQs, say you don't know and suggest the user contact the admissions office.\n\n");

        sb.append("FAQs:\n");
        sb.append(faqText);
        sb.append("\n\nUser question: ").append(userQuestion).append("\n");
        sb.append("Answer clearly, in 3–6 sentences, directly to the student.\n");

        return sb.toString();
    }

    private String callBedrockModel(String prompt) {
        try {
            // Build request body for Claude 3 via Bedrock
            JsonObject requestBody = new JsonObject();

            // REQUIRED for Anthropic models on Bedrock
            requestBody.addProperty("anthropic_version", "bedrock-2023-05-31");

            // messages: [{ role: "user", content: [{ type: "text", text: prompt }] }]
            JsonObject userMessage = new JsonObject();
            userMessage.addProperty("role", "user");

            JsonArray userContent = new JsonArray();
            JsonObject textObj = new JsonObject();
            textObj.addProperty("type", "text");
            textObj.addProperty("text", prompt);
            userContent.add(textObj);

            userMessage.add("content", userContent);

            JsonArray messages = new JsonArray();
            messages.add(userMessage);

            requestBody.add("messages", messages);
            requestBody.addProperty("max_tokens", 512);
            requestBody.addProperty("temperature", 0.3);

            String bodyString = requestBody.toString();

            InvokeModelRequest request = InvokeModelRequest.builder()
                    .modelId(modelId)  // from BEDROCK_MODEL_ID env var
                    .body(SdkBytes.fromUtf8String(bodyString))
                    .build();

            InvokeModelResponse response = bedrock.invokeModel(request);
            String responseJson = response.body().asUtf8String();

            // Parse Claude-style response: { "content": [ { "type":"text", "text":"..." } ], ... }
            JsonObject root = JsonParser.parseString(responseJson).getAsJsonObject();
            JsonArray content = root.getAsJsonArray("content");
            if (content != null && content.size() > 0) {
                JsonObject first = content.get(0).getAsJsonObject();
                if (first.has("text")) {
                    return first.get("text").getAsString();
                }
            }

            // Fallback if structure unexpected
            return "AI response (raw): " + responseJson;

        } catch (Exception e) {
            return "Sorry, I had an issue contacting the AI model: " + e.getMessage();
        }
    }


}

