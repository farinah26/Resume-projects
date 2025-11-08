package com.farina.feedback;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.comprehend.ComprehendClient;
import software.amazon.awssdk.services.comprehend.model.DetectSentimentRequest;
import software.amazon.awssdk.services.comprehend.model.DetectSentimentResponse;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.HashMap;
import java.util.Map;

public class LambdaHandler {
    public void handleRequest() throws Exception {
        String bucket = "farina-feedback-raw-20250929";
        String key = "2025/10/sample-feedback-001.csv";

        // Initialize AWS clients
        S3Client s3 = S3Client.builder().build();
        ComprehendClient comprehendClient = ComprehendClient.builder().build();
        DynamoDbClient dynamoDb = DynamoDbClient.builder().build();
        SnsClient sns = SnsClient.builder().build();

        // Read S3 CSV file
        Reader reader = new InputStreamReader(
            s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build())
        );

        Iterable<CSVRecord> records = CSVFormat.DEFAULT
            .withFirstRecordAsHeader()
            .withIgnoreHeaderCase()
            .withTrim()
            .parse(reader);

        for (CSVRecord record : records) {
            String feedbackId = record.get("FeedbackID");
            String customerName = record.get("CustomerName");
            String date = record.get("Date");
            String feedbackText = record.get("FeedbackText");
            String ratingStr = record.get("Rating").trim();

            // Ensure rating is numeric
            int rating = 0;
            try {
                rating = Integer.parseInt(ratingStr);
            } catch (NumberFormatException e) {
                System.err.println("Invalid rating for FeedbackID " + feedbackId + ": " + ratingStr);
                continue; // skip this record
            }

            // Detect sentiment
            DetectSentimentRequest request = DetectSentimentRequest.builder()
                .text(feedbackText)
                .languageCode("en")
                .build();
            DetectSentimentResponse response = comprehendClient.detectSentiment(request);
            String sentiment = response.sentimentAsString();

            // Prepare DynamoDB item
            Map<String, AttributeValue> item = new HashMap<>();
            item.put("FeedbackID", AttributeValue.builder().s(feedbackId).build());
            item.put("CustomerName", AttributeValue.builder().s(customerName).build());
            item.put("Date", AttributeValue.builder().s(date).build());
            item.put("FeedbackText", AttributeValue.builder().s(feedbackText).build());
            item.put("Rating", AttributeValue.builder().n(String.valueOf(rating)).build());
            item.put("Sentiment", AttributeValue.builder().s(sentiment).build());

            dynamoDb.putItem(PutItemRequest.builder()
                .tableName("FeedbackTable")
                .item(item)
                .build());

            // Send SNS alert if negative
            if (sentiment.equalsIgnoreCase("NEGATIVE")) {
                sns.publish(PublishRequest.builder()
                    .topicArn("arn:aws:sns:us-east-1:512795167881:NegativeFeedbackAlerts")
                    .message("Negative feedback detected: " + feedbackText)
                    .build());
            }
        }

        reader.close();
        System.out.println("✅ CSV processed successfully.");
    }
}
